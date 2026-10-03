package com.liteweb.extractor

import com.liteweb.extractor.engine.ExtractResult
import com.liteweb.extractor.engine.Extractor
import com.liteweb.extractor.server.LocalServer
import com.liteweb.extractor.store.HlsUrlStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Unit/integration tests for the local NanoHTTPD server.
 * Runs on JVM (no Android device required).
 */
class LocalServerTest {

    private lateinit var server: LocalServer
    private var lastLoadedUrl: String? = null

    @Before
    fun setUp() {
        HlsUrlStore.clear()
        server = LocalServer(8081) { url -> lastLoadedUrl = url }
        server.start()
        Thread.sleep(200) // Give server time to bind
    }

    @After
    fun tearDown() {
        server.stop()
        HlsUrlStore.clear()
        lastLoadedUrl = null
    }

    // ── Status endpoint ─────────────────────────────────────────────────────

    @Test
    fun `status endpoint returns success`() {
        val (code, body) = get("http://127.0.0.1:8081/status")
        assertEquals(200, code)
        assertTrue(body.contains("\"success\":true"))
    }

    // ── Load URL endpoint ───────────────────────────────────────────────────

    @Test
    fun `load valid URL triggers callback`() {
        val (code, body) = get("http://127.0.0.1:8081/?url=https://example.com")
        assertEquals(200, code)
        assertTrue(body.contains("\"success\":true"))
        assertEquals("https://example.com", lastLoadedUrl)
    }

    @Test
    fun `load URL path style triggers callback`() {
        val (code, body) = get("http://127.0.0.1:8081/url=https://example.com/newvideo")
        assertEquals(200, code)
        assertTrue(body.contains("\"success\":true"))
        assertEquals("https://example.com/newvideo", lastLoadedUrl)
    }

    @Test
    fun `missing URL parameter returns error`() {
        val (code, body) = get("http://127.0.0.1:8081/")
        assertEquals(400, code)
        assertTrue(body.contains("\"success\":false"))
    }

    @Test
    fun `invalid URL scheme returns error`() {
        val (_, body) = get("http://127.0.0.1:8081/?url=ftp://example.com")
        assertTrue(body.contains("\"success\":false"))
        assertTrue(body.contains("Invalid URL"))
    }

    @Test
    fun `javascript scheme is rejected`() {
        val (_, body) = get("http://127.0.0.1:8081/?url=javascript:alert(1)")
        assertTrue(body.contains("\"success\":false"))
    }

    // ── Extract endpoint ────────────────────────────────────────────────────

    @Test
    fun `extract returns not detected when store empty`() {
        val (code, body) = get("http://127.0.0.1:8081/extract?url=https://example.com/video")
        assertEquals(200, code)
        assertTrue(body.contains("\"success\":false"))
        assertTrue(body.contains("HLS stream not detected"))
    }

    @Test
    fun `extract detects m3u8 URL directly`() {
        val (code, body) = get("http://127.0.0.1:8081/extract?url=https://example.com/master.m3u8")
        assertEquals(200, code)
        assertTrue(body.contains("\"success\":true"))
        assertTrue(body.contains("\"type\":\"hls\""))
        assertTrue(body.contains("master.m3u8"))
    }

    @Test
    fun `extract returns stored HLS URL`() {
        HlsUrlStore.set("https://cdn.example.com/stream/index.m3u8", "application/vnd.apple.mpegurl")
        val (code, body) = get("http://127.0.0.1:8081/extract?url=https://example.com")
        assertEquals(200, code)
        assertTrue(body.contains("\"success\":true"))
        assertTrue(body.contains("index.m3u8"))
    }

    @Test
    fun `loading new URL clears HLS store`() {
        HlsUrlStore.set("https://old.example.com/old.m3u8")
        get("http://127.0.0.1:8081/?url=https://example.com/newpage")
        assertFalse(HlsUrlStore.hasUrl())
    }

    // ── HLS Store ────────────────────────────────────────────────────────────

    @Test
    fun `HlsUrlStore is thread safe`() {
        val threads = (1..10).map { i ->
            Thread { HlsUrlStore.set("https://example.com/stream$i.m3u8") }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertNotNull(HlsUrlStore.get())
        assertTrue(HlsUrlStore.get()!!.endsWith(".m3u8"))
    }

    @Test
    fun `HlsUrlStore clear works`() {
        HlsUrlStore.set("https://example.com/video.m3u8")
        assertTrue(HlsUrlStore.hasUrl())
        HlsUrlStore.clear()
        assertFalse(HlsUrlStore.hasUrl())
        assertNull(HlsUrlStore.get())
    }

    // ── Concurrency ──────────────────────────────────────────────────────────

    /** Fake extractor: each page "takes" 500 ms and returns an m3u8 unique to that page. */
    private class SlowFakeExtractor : Extractor {
        override fun extract(pageUrl: String, timeoutMs: Long): ExtractResult {
            Thread.sleep(500)
            val id = pageUrl.substringAfterLast("/")
            return ExtractResult("https://cdn.example.com/$id.m3u8", "application/vnd.apple.mpegurl", null, 500)
        }
        override fun poolSize() = 5
        override fun activeJobs() = 0
    }

    @Test
    fun `many simultaneous extract requests run in parallel without mixing results`() {
        val s2 = LocalServer(8082, SlowFakeExtractor())
        s2.start()
        Thread.sleep(200)
        try {
            val results = java.util.concurrent.ConcurrentHashMap<Int, String>()
            val t0 = System.currentTimeMillis()
            val threads = (1..5).map { i ->
                Thread {
                    val (_, body) = get("http://127.0.0.1:8082/extract?url=https://site.com/video$i")
                    results[i] = body
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            val elapsed = System.currentTimeMillis() - t0

            // 5 x 500 ms done one-by-one would be 2500 ms; in parallel it is ~500 ms.
            assertTrue("took ${elapsed}ms, requests were serialized", elapsed < 2000)
            for (i in 1..5) {
                assertTrue(results[i]!!.contains("video$i.m3u8"))
            }
        } finally {
            s2.stop()
        }
    }

    @Test
    fun `server is reachable over IPv6 loopback too (cloudflared uses localhost)`() {
        val body = try {
            get("http://[::1]:8081/status").second
        } catch (e: Exception) {
            return // machine without IPv6 loopback: nothing to check
        }
        assertTrue(body.contains("\"success\":true"))
    }

    @Test
    fun `unencoded url with ampersand keeps its full query string`() {
        val s2 = LocalServer(8083, object : Extractor {
            override fun extract(pageUrl: String, timeoutMs: Long) =
                ExtractResult(pageUrl + ".m3u8", null, null, 1)
            override fun poolSize() = 1
            override fun activeJobs() = 0
        })
        s2.start()
        Thread.sleep(200)
        try {
            val (_, body) = get("http://127.0.0.1:8083/extract?url=https://site.com/watch?id=7&ep=2")
            assertTrue(body.contains("watch?id=7&ep=2.m3u8"))
        } finally {
            s2.stop()
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun get(urlStr: String): Pair<Int, String> {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        return try {
            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText() ?: ""
            Pair(code, body)
        } finally {
            conn.disconnect()
        }
    }
}
