package com.liteweb.extractor.server

import com.liteweb.extractor.engine.Extractor
import com.liteweb.extractor.store.HlsUrlStore
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Lightweight NanoHTTPD server. Handles MANY requests at once: NanoHTTPD runs one thread per
 * connection, and every /extract call gets its own WebView from the [Extractor] pool.
 *
 * Binds to the wildcard address so BOTH 127.0.0.1 and ::1 work. That matters for
 *   cloudflared tunnel --url http://localhost:8080
 * because "localhost" often resolves to ::1 first, which a 127.0.0.1-only server refuses.
 *
 * Endpoints:
 *   GET /extract?url=PAGE[&timeout=SECONDS] → open PAGE, wait for the .m3u8, return it (concurrent-safe)
 *   GET /extract                            → latest detected HLS URL (legacy)
 *   GET /?url=PAGE   or   /url=PAGE         → start loading PAGE in the background (legacy)
 *   GET /status                             → health check
 */
class LocalServer(
    port: Int = DEFAULT_PORT,
    private val extractor: Extractor? = null,
    private val onLoadUrl: (String) -> Unit = {}
) : NanoHTTPD(null, port) {

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_TIMEOUT_SEC = 25L
        private const val MIN_TIMEOUT_SEC = 3L
        private const val MAX_TIMEOUT_SEC = 90L
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (session.method == Method.OPTIONS) {
                cors(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""))
            } else {
                handleRequest(session)
            }
        } catch (e: Exception) {
            errorResponse("Internal server error: ${e.message}", Response.Status.INTERNAL_ERROR)
        }
    }

    private fun handleRequest(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        return when {
            uri.startsWith("/extract") -> handleExtract(session)
            uri == "/status" -> handleStatus()
            else -> handleLoad(session, uri)
        }
    }

    // ── /?url=  and  /url=  ────────────────────────────────────────────────

    private fun handleLoad(session: IHTTPSession, uri: String): Response {
        val url = readUrl(session)
            ?: extractUrlFromPath(session, uri)
            ?: return errorResponse(
                "Missing 'url' parameter. Use /extract?url=https://site/page",
                Response.Status.BAD_REQUEST
            )

        if (!isValidUrl(url)) {
            return errorResponse("Invalid URL: must start with http:// or https://", Response.Status.BAD_REQUEST)
        }

        HlsUrlStore.clear()
        onLoadUrl(url)

        // Run in the background so this request returns immediately; /extract (no url) reads the result.
        extractor?.let { ex ->
            Thread({ ex.extract(url, DEFAULT_TIMEOUT_SEC * 1000) }, "liteweb-bg-load").start()
        }

        val json = JSONObject().apply {
            put("success", true)
            put("url", url)
            put("message", "URL loaded in WebView")
        }
        return jsonResponse(json)
    }

    // ── /extract ────────────────────────────────────────────────────────────

    private fun handleExtract(session: IHTTPSession): Response {
        val requestedUrl = readUrl(session)

        if (requestedUrl != null) {
            if (!isValidUrl(requestedUrl)) {
                return errorResponse("Invalid URL: must start with http:// or https://", Response.Status.BAD_REQUEST)
            }
            // The URL itself is a playlist: return it right away.
            if (requestedUrl.endsWith(".m3u8") || requestedUrl.contains(".m3u8?")) {
                return jsonResponse(JSONObject().apply {
                    put("success", true)
                    put("type", "hls")
                    put("url", requestedUrl)
                    put("contentType", "application/vnd.apple.mpegurl")
                    put("source", "url_pattern")
                })
            }

            // Real extraction: open the page in a pooled WebView and wait for the stream.
            val ex = extractor
            if (ex != null) {
                val timeoutSec = (session.parameters["timeout"]?.firstOrNull()?.toLongOrNull()
                    ?: DEFAULT_TIMEOUT_SEC).coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)
                val r = ex.extract(requestedUrl, timeoutSec * 1000)
                return if (r.hlsUrl != null) {
                    jsonResponse(JSONObject().apply {
                        put("success", true)
                        put("type", "hls")
                        put("url", r.hlsUrl)
                        put("contentType", r.contentType ?: "application/vnd.apple.mpegurl")
                        put("source", "webview_detection")
                        put("elapsedMs", r.elapsedMs)
                    })
                } else {
                    jsonResponse(
                        JSONObject().apply {
                            put("success", false)
                            put("url", JSONObject.NULL)
                            put("error", r.error ?: "HLS stream not detected")
                            put("elapsedMs", r.elapsedMs)
                        },
                        if (r.busy) Response.Status.SERVICE_UNAVAILABLE else Response.Status.OK
                    )
                }
            }
        }

        // Legacy: latest detected HLS URL from the store.
        val hlsUrl = HlsUrlStore.get()
        return if (hlsUrl != null) {
            jsonResponse(JSONObject().apply {
                put("success", true)
                put("type", "hls")
                put("url", hlsUrl)
                put("contentType", HlsUrlStore.getContentType() ?: "application/vnd.apple.mpegurl")
                put("source", "webview_detection")
            })
        } else {
            jsonResponse(JSONObject().apply {
                put("success", false)
                put("url", JSONObject.NULL)
                put("error", "HLS stream not detected")
            })
        }
    }

    private fun handleStatus(): Response {
        return jsonResponse(JSONObject().apply {
            put("success", true)
            put("server", "LiteWebExtractor")
            put("version", "1.1.0")
            put("port", if (wasStarted()) listeningPort else DEFAULT_PORT)
            put("hlsDetected", HlsUrlStore.hasUrl())
            put("workers", extractor?.poolSize() ?: 0)
            put("activeJobs", extractor?.activeJobs() ?: 0)
        })
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Reads ?url=... Accepts a properly encoded URL (url=https%3A%2F%2Fsite%2Fp%3Fa%3D1%26b%3D2)
     * AND a raw unencoded one (url=https://site/p?a=1&b=2), where the normal parser would
     * cut the URL at the first '&'.
     */
    private fun readUrl(session: IHTTPSession): String? {
        val raw = session.queryParameterString
        if (raw != null) {
            val start = when {
                raw.startsWith("url=") -> 4
                raw.contains("&url=") -> raw.indexOf("&url=") + 5
                else -> -1
            }
            if (start >= 0) {
                var v = raw.substring(start)
                val t = v.indexOf("&timeout=")
                if (t >= 0) v = v.substring(0, t)
                v = v.trim()
                return if (v.startsWith("http://") || v.startsWith("https://")) {
                    v
                } else {
                    try { URLDecoder.decode(v, "UTF-8") } catch (e: Exception) { v }
                }
            }
        }
        return session.parameters["url"]?.firstOrNull()
    }

    /** /url=https://example.com/video?x=1 (legacy path style) */
    private fun extractUrlFromPath(session: IHTTPSession, uri: String): String? {
        val prefix = "/url="
        if (!uri.startsWith(prefix)) return null
        val q = session.queryParameterString
        return (uri.substring(prefix.length) + if (q != null) "?$q" else "").trim()
    }

    private fun isValidUrl(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    private fun cors(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "*")
        return r
    }

    private fun jsonResponse(json: JSONObject, status: Response.IStatus = Response.Status.OK): Response =
        cors(newFixedLengthResponse(status, "application/json", json.toString()))

    private fun errorResponse(message: String, status: Response.IStatus): Response =
        jsonResponse(JSONObject().apply {
            put("success", false)
            put("error", message)
        }, status)
}
