package com.lite.streamview

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.lite.streamview.interceptor.RequestInterceptor
import com.lite.streamview.server.LiteHttpServer
import com.lite.streamview.store.HlsStream
import com.lite.streamview.store.HlsUrlStore

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var btnOpen: Button
    private lateinit var tvHlsStatus: TextView
    private lateinit var btnCopyHls: Button
    private lateinit var tvServerStatus: TextView
    private lateinit var btnCopyServer: Button
    private lateinit var switchLiteMode: SwitchMaterial
    private lateinit var progressBar: ProgressBar
    private lateinit var panelBody: View
    private lateinit var btnTogglePanel: TextView
    private lateinit var fullscreenContainer: FrameLayout

    private val hlsUrlStore = HlsUrlStore()
    private lateinit var requestInterceptor: RequestInterceptor
    private var httpServer: LiteHttpServer? = null

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initInterceptor()
        initWebView()
        initServer()
        setupListeners()
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        etUrl = findViewById(R.id.etUrl)
        btnOpen = findViewById(R.id.btnOpen)
        tvHlsStatus = findViewById(R.id.tvHlsStatus)
        btnCopyHls = findViewById(R.id.btnCopyHls)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        btnCopyServer = findViewById(R.id.btnCopyServer)
        switchLiteMode = findViewById(R.id.switchLiteMode)
        progressBar = findViewById(R.id.progressBar)
        panelBody = findViewById(R.id.panelBody)
        btnTogglePanel = findViewById(R.id.btnTogglePanel)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
    }

    private fun initInterceptor() {
        requestInterceptor = RequestInterceptor(
            hlsUrlStore = hlsUrlStore,
            isLiteModeEnabled = switchLiteMode.isChecked
        )

        hlsUrlStore.setListener { stream ->
            runOnUiThread {
                updateHlsUI(stream)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportMultipleWindows(false)
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.databaseEnabled = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadsImagesAutomatically = !requestInterceptor.isLiteModeEnabled

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request != null) {
                    val intercepted = requestInterceptor.intercept(request)
                    if (intercepted != null) {
                        return intercepted
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
            }

            override fun onHideCustomView() {
                if (customView == null) return
                fullscreenContainer.removeView(customView)
                customView = null
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            }
        }
    }

    private fun initServer() {
        try {
            val server = LiteHttpServer(
                port = 8080,
                hlsUrlStore = hlsUrlStore,
                onNavigateRequested = { url ->
                    runOnUiThread {
                        loadUrl(url)
                    }
                },
                onLiteModeChanged = { enabled ->
                    runOnUiThread {
                        switchLiteMode.isChecked = enabled
                        applyLiteMode(enabled)
                    }
                }
            )
            server.isLiteMode = switchLiteMode.isChecked
            server.start()
            httpServer = server
            tvServerStatus.text = "● Running :8080"
            tvServerStatus.setTextColor(Color.parseColor("#2E7D32"))
        } catch (e: Exception) {
            tvServerStatus.text = "● Error: ${e.message ?: "Failed to start"}"
            tvServerStatus.setTextColor(Color.parseColor("#C62828"))
        }
    }

    private fun setupListeners() {
        btnOpen.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                val formatted = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    "https://$url"
                } else {
                    url
                }
                loadUrl(formatted)
            }
        }

        btnTogglePanel.setOnClickListener {
            if (panelBody.visibility == View.VISIBLE) {
                panelBody.visibility = View.GONE
                btnTogglePanel.text = "[ Show Panel ]"
            } else {
                panelBody.visibility = View.VISIBLE
                btnTogglePanel.text = "[ Hide Panel ]"
            }
        }

        switchLiteMode.setOnCheckedChangeListener { _, isChecked ->
            applyLiteMode(isChecked)
            httpServer?.isLiteMode = isChecked
        }

        btnCopyServer.setOnClickListener {
            copyToClipboard("Server URL", "http://127.0.0.1:8080")
            Toast.makeText(this, "Copied http://127.0.0.1:8080", Toast.LENGTH_SHORT).show()
        }

        btnCopyHls.setOnClickListener {
            val hls = hlsUrlStore.getLatestHls()
            if (hls != null) {
                copyToClipboard("HLS Stream URL", hls.url)
                Toast.makeText(this, "Copied HLS stream URL", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyLiteMode(enabled: Boolean) {
        requestInterceptor.isLiteModeEnabled = enabled
        webView.settings.loadsImagesAutomatically = !enabled
    }

    private fun loadUrl(url: String) {
        etUrl.setText(url)
        resetHlsUI()
        hlsUrlStore.clear()
        webView.loadUrl(url)
    }

    private fun updateHlsUI(stream: HlsStream) {
        tvHlsStatus.text = "● ${stream.url}"
        tvHlsStatus.setTextColor(Color.parseColor("#2E7D32"))
        btnCopyHls.visibility = View.VISIBLE
    }

    private fun resetHlsUI() {
        tvHlsStatus.text = getString(R.string.hls_not_detected)
        tvHlsStatus.setTextColor(Color.parseColor("#757575"))
        btnCopyHls.visibility = View.GONE
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) {
            (webView.webChromeClient as? WebChromeClient)?.onHideCustomView()
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.pauseTimers()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            httpServer?.stop()
        } catch (_: Exception) {}
        webView.destroy()
    }
}
