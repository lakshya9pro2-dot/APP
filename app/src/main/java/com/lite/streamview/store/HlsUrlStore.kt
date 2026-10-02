package com.lite.streamview.store

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class HlsStream(
    val url: String,
    val contentType: String = "application/vnd.apple.mpegurl",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Thread-safe in-memory store for detected HLS streams.
 */
class HlsUrlStore {
    private val latestHls = AtomicReference<HlsStream?>(null)
    private var latch = AtomicReference(CountDownLatch(1))
    private var onHlsDetectedListener: ((HlsStream) -> Unit)? = null

    fun setListener(listener: ((HlsStream) -> Unit)?) {
        this.onHlsDetectedListener = listener
    }

    fun setLatestHls(stream: HlsStream) {
        latestHls.set(stream)
        latch.get().countDown()
        onHlsDetectedListener?.invoke(stream)
    }

    fun getLatestHls(): HlsStream? = latestHls.get()

    fun clear() {
        latestHls.set(null)
        latch.set(CountDownLatch(1))
    }

    /**
     * Waits up to [timeoutSeconds] for an HLS stream to be detected.
     * Useful for synchronous extraction calls e.g. /extract?url=...
     */
    fun waitForHls(timeoutSeconds: Long = 5): HlsStream? {
        latestHls.get()?.let { return it }
        try {
            latch.get().await(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return latestHls.get()
    }
}
