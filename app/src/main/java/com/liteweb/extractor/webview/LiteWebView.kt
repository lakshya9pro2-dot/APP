package com.liteweb.extractor.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.webkit.*
import com.liteweb.extractor.interceptor.RequestInterceptor

/**
 * Lightweight WebView with:
 * - Minimal configuration for low-end devices
 * - Ad / image request blocking
 * - HLS URL detection via shouldInterceptRequest
 */
class LiteWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    var liteModeEnabled: Boolean = false
    var onPageStarted: ((String) -> Unit)? = null
    var onPageFinished: ((String) -> Unit)? = null
    var onHlsDetected: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Per-WebView HLS listener. Each pooled WebView gets its own listener per job,
     * so concurrent extractions never see each other's streams.
     */
    @Volatile
    var hlsListener: ((url: String, contentType: String?) -> Unit)? = null

    init {
        configure()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure() {
        settings.apply {
            // Essential for most video sites
            javaScriptEnabled = true
            domStorageEnabled = true

            // HTML5 video support
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false

            // Reduce memory usage
            cacheMode = WebSettings.LOAD_DEFAULT
            databaseEnabled = false
            geolocationEnabled = false

            // Desktop UA for better compatibility with video players
            userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36"

            // Layout
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = false
        }

        webViewClient = LiteWebViewClient()
        webChromeClient = LiteWebChromeClient()

        // Enable hardware acceleration for smoother playback
        setLayerType(LAYER_TYPE_HARDWARE, null)

        // Clear on start
        clearCache(false)
    }

    fun loadSite(url: String) {
        loadUrl(url)
    }

    private fun reportHls(url: String, contentType: String?) {
        hlsListener?.invoke(url, contentType)
        onHlsDetected?.invoke(url)
    }

    fun applyLiteMode(enabled: Boolean) {
        liteModeEnabled = enabled
        settings.apply {
            // In lite mode, reduce cache aggressiveness
            cacheMode = if (enabled) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        }
    }

    override fun destroy() {
        stopLoading()
        clearHistory()
        clearCache(true)
        loadUrl("about:blank")
        super.destroy()
    }

    inner class LiteWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            // HLS detection + ad/image blocking. Runs on a WebView background thread.
            return RequestInterceptor.intercept(request, liteModeEnabled) { u, ct ->
                reportHls(u, ct)
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            onPageStarted?.invoke(url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            onPageFinished?.invoke(url)
        }

        override fun onReceivedError(
            view: WebView,
            errorCode: Int,
            description: String,
            failingUrl: String
        ) {
            onError?.invoke("Error $errorCode: $description")
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) {
            val url = request.url?.toString() ?: return
            val status = errorResponse.statusCode
            val contentType = errorResponse.mimeType?.lowercase() ?: ""

            // Detect HLS from HTTP response Content-Type header
            RequestInterceptor.onResponseReceived(url, contentType) { u, ct ->
                reportHls(u, ct)
            }
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url?.toString() ?: return false
            val scheme = request.url?.scheme?.lowercase() ?: ""
            // Only allow http/https
            return scheme != "http" && scheme != "https"
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            return !url.startsWith("http://") && !url.startsWith("https://")
        }
    }

    inner class LiteWebChromeClient : WebChromeClient() {
        // Minimal chrome client — only override what's needed
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            // Progress updates handled in MainActivity
        }
    }
}
