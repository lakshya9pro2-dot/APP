package com.liteweb.extractor.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.liteweb.extractor.store.HlsUrlStore
import com.liteweb.extractor.webview.LiteWebView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs many extractions at once.
 *
 *  - A pool of [poolSize] headless WebViews. Each job gets its OWN WebView, so
 *    two requests can never overwrite each other's result (the old code had one
 *    WebView and one global "latest URL").
 *  - More requests than WebViews wait in a fair FIFO queue (up to [QUEUE_TIMEOUT_MS]).
 *  - Two requests for the same page URL at the same time share one job.
 *
 * [extract] blocks, so call it from NanoHTTPD's per-connection threads, never the main thread.
 */
class ExtractorEngine(
    context: Context,
    private val poolSize: Int = DEFAULT_POOL_SIZE
) : Extractor {

    companion object {
        const val DEFAULT_POOL_SIZE = 3
        const val QUEUE_TIMEOUT_MS = 60_000L
    }

    private class Job {
        val latch = CountDownLatch(1)
        @Volatile var result: ExtractResult? = null
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val permits = Semaphore(poolSize, true)
    private val idle = ConcurrentLinkedQueue<LiteWebView>()
    private val all = ConcurrentLinkedQueue<LiteWebView>()
    private val inFlight = ConcurrentHashMap<String, Job>()
    private val active = AtomicInteger(0)
    @Volatile private var closed = false

    override fun poolSize(): Int = poolSize
    override fun activeJobs(): Int = active.get()

    override fun extract(pageUrl: String, timeoutMs: Long): ExtractResult {
        if (closed) return ExtractResult(null, null, "Engine stopped", 0)

        val mine = Job()
        val existing = inFlight.putIfAbsent(pageUrl, mine)
        if (existing != null) {
            // Same page already being extracted: wait for that job instead of starting another.
            existing.latch.await(timeoutMs + QUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return existing.result ?: ExtractResult(null, null, "Timed out waiting for shared job", 0)
        }

        try {
            mine.result = runJob(pageUrl, timeoutMs)
        } catch (e: Exception) {
            mine.result = ExtractResult(null, null, "Extraction failed: ${e.message}", 0)
        } finally {
            if (mine.result == null) mine.result = ExtractResult(null, null, "Extraction failed", 0)
            inFlight.remove(pageUrl)
            mine.latch.countDown()
        }
        return mine.result!!
    }

    private fun runJob(pageUrl: String, timeoutMs: Long): ExtractResult {
        val start = SystemClock.elapsedRealtime()

        if (!permits.tryAcquire(QUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return ExtractResult(
                null, null, "Server busy: all $poolSize workers in use",
                SystemClock.elapsedRealtime() - start, busy = true
            )
        }
        active.incrementAndGet()
        var view: LiteWebView? = null
        try {
            val w: LiteWebView = idle.poll() ?: onMain { LiteWebView(appContext).also { all.add(it) } }
            view = w

            val hit = AtomicReference<Pair<String, String?>?>(null)
            val found = CountDownLatch(1)
            onMain {
                w.hlsListener = { url, ct ->
                    if (hit.compareAndSet(null, Pair(url, ct))) found.countDown()
                }
                w.onResume()
                w.resumeTimers()
                w.loadSite(pageUrl)
            }

            found.await(timeoutMs, TimeUnit.MILLISECONDS)
            val elapsed = SystemClock.elapsedRealtime() - start
            val found1 = hit.get()
            return if (found1 != null) {
                HlsUrlStore.set(found1.first, found1.second) // keeps legacy "latest" lookups working
                ExtractResult(found1.first, found1.second, null, elapsed)
            } else {
                ExtractResult(null, null, "HLS stream not detected", elapsed)
            }
        } finally {
            val v = view
            if (v != null) {
                onMain {
                    v.hlsListener = null
                    v.stopLoading()
                    v.loadUrl("about:blank")
                    v.clearHistory()
                }
                idle.offer(v)
            }
            active.decrementAndGet()
            permits.release()
        }
    }

    fun shutdown() {
        closed = true
        val views = all.toList()
        all.clear()
        idle.clear()
        main.post { views.forEach { it.destroy() } }
    }

    /** Run [block] on the main thread and wait for the result. */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        val out = AtomicReference<T?>(null)
        val err = AtomicReference<Throwable?>(null)
        main.post {
            try { out.set(block()) } catch (t: Throwable) { err.set(t) } finally { latch.countDown() }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) throw IllegalStateException("Main thread busy")
        err.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return out.get() as T
    }
}
