package com.liteweb.extractor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.liteweb.extractor.engine.ExtractorEngine
import com.liteweb.extractor.server.LocalServer
import com.liteweb.extractor.ui.MainActivity
import java.io.IOException

/**
 * Foreground service that owns the HTTP server and the WebView pool.
 *
 * Why a service: when the app was in the background (you were in Termux running cloudflared),
 * Android paused the WebView and could kill the process, so the tunnel pointed at a dead server.
 */
class ExtractorService : Service() {

    companion object {
        private const val TAG = "LiteWebExtractor"
        private const val CHANNEL_ID = "liteweb_server"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.liteweb.extractor.STOP"

        @Volatile var running = false
            private set

        /** UI hooks (set by MainActivity, invoked on the main thread). */
        @Volatile var statusListener: ((running: Boolean) -> Unit)? = null
        @Volatile var onLoadUrl: ((String) -> Unit)? = null

        fun start(context: Context) {
            val i = Intent(context, ExtractorService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var server: LocalServer? = null
    private var engine: ExtractorEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (server == null) startServer()
        return START_STICKY
    }

    private fun startServer() {
        val eng = ExtractorEngine(this)
        val srv = LocalServer(LocalServer.DEFAULT_PORT, eng) { url ->
            main.post { onLoadUrl?.invoke(url) }
        }
        try {
            srv.start()
            engine = eng
            server = srv
            setRunning(true)
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiteWebExtractor:server")
                .apply { acquire() }
            Log.i(TAG, "Server listening on :${LocalServer.DEFAULT_PORT}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start server: ${e.message}")
            eng.shutdown()
            setRunning(false)
            stopSelf()
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Extractor server", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ExtractorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = newBuilder()
            .setContentTitle("LiteWeb Extractor")
            .setContentText("Server running on port ${LocalServer.DEFAULT_PORT}")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun newBuilder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)

    private fun setRunning(value: Boolean) {
        running = value
        main.post { statusListener?.invoke(value) }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        engine?.shutdown()
        engine = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        setRunning(false)
        super.onDestroy()
    }
}
