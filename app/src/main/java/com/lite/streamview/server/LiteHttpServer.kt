package com.lite.streamview.server

import com.lite.streamview.store.HlsUrlStore
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * Embedded NanoHTTPD server bound to localhost (127.0.0.1).
 * Exposes lightweight REST endpoints to control WebView and extract HLS streams.
 */
class LiteHttpServer(
    val port: Int = 8080,
    private val hlsUrlStore: HlsUrlStore,
    private val onNavigateRequested: (String) -> Unit,
    private val onLiteModeChanged: ((Boolean) -> Unit)? = null
) : NanoHTTPD("127.0.0.1", port) {

    @Volatile
    var currentUrl: String? = null
        private set

    @Volatile
    var isLiteMode: Boolean = true

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parms

        try {
            // Endpoint 1: /extract?url=...
            if (uri == "/extract" || uri.startsWith("/extract")) {
                val targetUrl = params["url"] ?: extractRawUrlFromQuery(session.queryParameterString)
                if (!targetUrl.isNullOrBlank()) {
                    val validated = sanitizeUrl(targetUrl)
                    if (validated != null && validated != currentUrl) {
                        currentUrl = validated
                        hlsUrlStore.clear()
                        onNavigateRequested(validated)
                    }
                }

                // Wait up to timeoutSec (default 5s) for discovery
                val timeoutSec = params["timeout"]?.toLongOrNull() ?: 5L
                val stream = hlsUrlStore.waitForHls(timeoutSec)

                val json = JSONObject()
                if (stream != null) {
                    json.put("success", true)
                    json.put("type", "hls")
                    json.put("url", stream.url)
                    json.put("contentType", stream.contentType)
                } else {
                    json.put("success", false)
                    json.put("url", JSONObject.NULL)
                    json.put("error", "HLS stream not detected")
                }
                return createJsonResponse(Response.Status.OK, json.toString())
            }

            // Endpoint 2: /?url=... or /url=...
            val requestedUrl = params["url"]
                ?: extractUrlFromPath(uri)
                ?: extractRawUrlFromQuery(session.queryParameterString)

            if (!requestedUrl.isNullOrBlank()) {
                val valid = sanitizeUrl(requestedUrl)
                return if (valid != null) {
                    currentUrl = valid
                    hlsUrlStore.clear()
                    onNavigateRequested(valid)

                    val json = JSONObject().apply {
                        put("success", true)
                        put("url", valid)
                    }
                    createJsonResponse(Response.Status.OK, json.toString())
                } else {
                    val json = JSONObject().apply {
                        put("success", false)
                        put("error", "Invalid destination URL. Only http:// and https:// URLs are allowed.")
                    }
                    createJsonResponse(Response.Status.BAD_REQUEST, json.toString())
                }
            }

            // Endpoint 3: /status
            if (uri == "/status") {
                val latestHls = hlsUrlStore.getLatestHls()
                val json = JSONObject().apply {
                    put("success", true)
                    put("status", "running")
                    put("host", "127.0.0.1")
                    put("port", port)
                    put("liteMode", isLiteMode)
                    put("currentUrl", currentUrl ?: JSONObject.NULL)
                    put("hlsDetected", latestHls != null)
                    put("hlsUrl", latestHls?.url ?: JSONObject.NULL)
                }
                return createJsonResponse(Response.Status.OK, json.toString())
            }

            // Endpoint 4: /mode?lite=on|off or /lite?enabled=true|false
            if (uri == "/mode" || uri == "/lite") {
                val liteParam = params["lite"] ?: params["enabled"]
                if (liteParam != null) {
                    val enabled = liteParam.equals("on", true) || liteParam.equals("true", true) || liteParam == "1"
                    isLiteMode = enabled
                    onLiteModeChanged?.invoke(enabled)
                    val json = JSONObject().apply {
                        put("success", true)
                        put("liteMode", enabled)
                    }
                    return createJsonResponse(Response.Status.OK, json.toString())
                }
            }

            // Root info
            val defaultJson = JSONObject().apply {
                put("app", "LiteWebView")
                put("status", "running")
                put("endpoints", listOf(
                    "GET /?url=https://example.com",
                    "GET /extract?url=https://example.com",
                    "GET /status",
                    "GET /mode?lite=on|off"
                ))
            }
            return createJsonResponse(Response.Status.OK, defaultJson.toString())

        } catch (e: Exception) {
            val errorJson = JSONObject().apply {
                put("success", false)
                put("error", e.message ?: "Internal server error")
            }
            return createJsonResponse(Response.Status.INTERNAL_ERROR, errorJson.toString())
        }
    }

    fun sanitizeUrl(input: String): String? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return try {
            val uri = URI(trimmed)
            if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) trimmed else null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractUrlFromPath(uri: String): String? {
        if (uri.startsWith("/url=")) {
            return uri.substring(5)
        }
        return null
    }

    private fun extractRawUrlFromQuery(queryString: String?): String? {
        if (queryString.isNullOrBlank()) return null
        val prefix = "url="
        val index = queryString.indexOf(prefix)
        if (index != -1) {
            val raw = queryString.substring(index + prefix.length)
            return try {
                URLDecoder.decode(raw, "UTF-8")
            } catch (_: Exception) {
                raw
            }
        }
        return null
    }

    private fun createJsonResponse(status: Response.Status, body: String): Response {
        val resp = newFixedLengthResponse(status, "application/json", body)
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        return resp
    }
}
