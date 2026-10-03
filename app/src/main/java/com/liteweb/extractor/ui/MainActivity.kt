package com.liteweb.extractor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.liteweb.extractor.R
import com.liteweb.extractor.databinding.ActivityMainBinding
import com.liteweb.extractor.server.LocalServer
import com.liteweb.extractor.service.ExtractorService
import com.liteweb.extractor.store.HlsUrlStore
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())
    private var liteModeEnabled = false

    companion object {
        private const val TAG = "LiteWebExtractor"
        private const val SERVER_ADDRESS = "http://127.0.0.1:${LocalServer.DEFAULT_PORT}"
        private const val PREFS_NAME = "liteweb_prefs"
        private const val PREF_LITE_MODE = "lite_mode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Restore preferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        liteModeEnabled = prefs.getBoolean(PREF_LITE_MODE, false)

        setupWebView()
        setupUI()
        startServer()
    }

    private fun setupWebView() {
        binding.webView.apply {
            applyLiteMode(liteModeEnabled)

            onPageStarted = { url ->
                mainHandler.post {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.urlInput.setText(url)
                    binding.hlsStatus.text = getString(R.string.hls_detecting)
                    binding.hlsStatusDot.setBackgroundResource(R.drawable.dot_yellow)
                }
            }

            onPageFinished = { _ ->
                mainHandler.post {
                    binding.progressBar.visibility = View.GONE
                    if (!HlsUrlStore.hasUrl()) {
                        binding.hlsStatus.text = getString(R.string.hls_not_detected)
                        binding.hlsStatusDot.setBackgroundResource(R.drawable.dot_red)
                    }
                }
            }

            onHlsDetected = { url ->
                mainHandler.post {
                    binding.hlsStatus.text = getString(R.string.hls_detected, url.take(60))
                    binding.hlsStatusDot.setBackgroundResource(R.drawable.dot_green)
                }
            }

            onError = { msg ->
                mainHandler.post {
                    binding.progressBar.visibility = View.GONE
                    Log.w(TAG, "WebView error: $msg")
                }
            }
        }
    }

    private fun setupUI() {
        // URL input — load on Enter/Go
        binding.urlInput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                loadUrlFromInput()
                true
            } else false
        }

        // Open Website button
        binding.btnOpenWebsite.setOnClickListener { loadUrlFromInput() }

        // Copy server address button
        binding.btnCopyServer.setOnClickListener {
            copyToClipboard("Server Address", SERVER_ADDRESS)
            Toast.makeText(this, "Copied: $SERVER_ADDRESS", Toast.LENGTH_SHORT).show()
        }

        // Lite Mode toggle
        binding.switchLiteMode.isChecked = liteModeEnabled
        updateLiteModeLabel()

        binding.switchLiteMode.setOnCheckedChangeListener { _, isChecked ->
            liteModeEnabled = isChecked
            binding.webView.applyLiteMode(isChecked)
            updateLiteModeLabel()
            // Persist preference
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_LITE_MODE, isChecked).apply()
        }

        // Server address label
        binding.serverAddress.text = SERVER_ADDRESS
    }

    private fun loadUrlFromInput() {
        var url = binding.urlInput.text.toString().trim()
        if (url.isEmpty()) return

        // Auto-prepend https:// if missing
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
            binding.urlInput.setText(url)
        }

        HlsUrlStore.clear()
        binding.webView.loadSite(url)
        hideKeyboard()
    }

    private fun startServer() {
        // Android 13+: the "server running" notification needs this permission to be visible.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        ExtractorService.statusListener = { running -> updateServerStatus(running) }
        // Mirror API-triggered loads in the visible WebView (optional; extraction itself is headless)
        ExtractorService.onLoadUrl = { url ->
            binding.webView.loadSite(url)
            binding.urlInput.setText(url)
        }
        updateServerStatus(ExtractorService.running)
        ExtractorService.start(this)
    }

    private fun updateServerStatus(running: Boolean) {
        if (running) {
            binding.serverStatus.text = getString(R.string.server_running)
            binding.serverStatusDot.setBackgroundResource(R.drawable.dot_green)
        } else {
            binding.serverStatus.text = getString(R.string.server_stopped)
            binding.serverStatusDot.setBackgroundResource(R.drawable.dot_red)
        }
    }

    private fun updateLiteModeLabel() {
        binding.liteModeLabel.text = if (liteModeEnabled)
            getString(R.string.lite_mode_on)
        else
            getString(R.string.lite_mode_off)
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        // The server lives in ExtractorService and keeps running; just detach the UI hooks.
        ExtractorService.statusListener = null
        ExtractorService.onLoadUrl = null
        binding.webView.destroy()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
