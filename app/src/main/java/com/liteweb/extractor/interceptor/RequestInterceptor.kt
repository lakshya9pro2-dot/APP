package com.liteweb.extractor.interceptor

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.liteweb.extractor.store.HlsUrlStore
import java.io.ByteArrayInputStream

/**
 * Lightweight request interceptor for the WebView.
 * Handles:
 * - Ad / tracking domain blocking
 * - Image blocking in Lite Mode
 * - HLS URL detection
 */
object RequestInterceptor {

    // Ad/tracking hostnames and path keywords to block
    private val AD_HOSTS = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "adservice.google.co",
        "pagead2.googlesyndication.com",
        "amazon-adsystem.com",
        "ads.yahoo.com",
        "advertising.com",
        "adnxs.com",
        "adsymptotic.com",
        "moatads.com",
        "outbrain.com",
        "taboola.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "revcontent.com",
        "criteo.com",
        "rubiconproject.com",
        "openx.net",
        "pubmatic.com",
        "quantserve.com",
        "scorecardresearch.com",
        "hotjar.com",
        "mouseflow.com",
        "fullstory.com",
        "loggly.com",
        "newrelic.com",
        "segment.com",
        "mixpanel.com"
    )

    private val AD_PATH_KEYWORDS = setOf(
        "/ads/", "/ad/", "/adserver/", "/banner/", "/banners/",
        "/tracking/", "/tracker/", "/analytics/", "/pixel/",
        "/popunder/", "/popup/", "/interstitial/"
    )

    // HLS MIME types
    private val HLS_MIME_TYPES = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "application/mpegurl"
    )

    /** Empty response to block a request (new instance each time: streams are single-use) */
    private fun blocked() = WebResourceResponse(
        "text/plain", "utf-8", 200, "OK",
        emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    /**
     * Main intercept function called from WebViewClient.
     * Returns a blocked response or null (allow through).
     */
    fun intercept(
        request: WebResourceRequest,
        liteModeEnabled: Boolean,
        onHls: ((url: String, contentType: String?) -> Unit)? = null
    ): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        val host = request.url?.host?.lowercase() ?: ""
        val path = request.url?.path?.lowercase() ?: ""
        val mimeType = request.requestHeaders?.get("Accept")?.lowercase() ?: ""

        // 1. Detect HLS by URL pattern
        if (url.endsWith(".m3u8") || url.contains(".m3u8?")) {
            onHls?.invoke(url, "application/vnd.apple.mpegurl")
        }

        // 2. Never block video/audio/HLS
        if (isMediaRequest(url, mimeType)) return null

        // 3. Ad host blocking (always on)
        if (isAdHost(host)) return blocked()

        // 4. Ad path blocking (always on)
        if (isAdPath(path)) return blocked()

        // 5. Image blocking in Lite Mode
        if (liteModeEnabled && isImageRequest(mimeType, url)) {
            return blocked()
        }

        return null // Allow through
    }

    /**
     * Called when a WebView response is received to detect HLS by Content-Type.
     */
    fun onResponseReceived(
        url: String,
        contentType: String?,
        onHls: ((url: String, contentType: String?) -> Unit)? = null
    ) {
        if (contentType == null) return
        val ct = contentType.lowercase().trim()
        if (HLS_MIME_TYPES.any { ct.startsWith(it) }) {
            HlsUrlStore.set(url, ct)
            onHls?.invoke(url, ct)
        }
    }

    private fun isAdHost(host: String): Boolean {
        return AD_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun isAdPath(path: String): Boolean {
        return AD_PATH_KEYWORDS.any { path.contains(it) }
    }

    private fun isImageRequest(mimeType: String, url: String): Boolean {
        if (mimeType.contains("image/")) return true
        val lowerUrl = url.lowercase()
        return lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".jpeg") ||
                lowerUrl.endsWith(".png") || lowerUrl.endsWith(".gif") ||
                lowerUrl.endsWith(".webp") || lowerUrl.endsWith(".svg") ||
                lowerUrl.endsWith(".ico") || lowerUrl.endsWith(".bmp")
    }

    private fun isMediaRequest(url: String, mimeType: String): Boolean {
        val lowerUrl = url.lowercase()
        // Never block media or HLS
        return lowerUrl.endsWith(".m3u8") ||
                lowerUrl.endsWith(".ts") ||
                lowerUrl.endsWith(".mp4") ||
                lowerUrl.endsWith(".mp3") ||
                lowerUrl.endsWith(".webm") ||
                lowerUrl.endsWith(".ogg") ||
                mimeType.contains("video/") ||
                mimeType.contains("audio/") ||
                mimeType.contains("mpegurl") ||
                mimeType.contains("x-mpegurl")
    }
}
