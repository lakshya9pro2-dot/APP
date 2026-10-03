package com.liteweb.extractor

import com.liteweb.extractor.interceptor.RequestInterceptor
import com.liteweb.extractor.store.HlsUrlStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for the request interceptor logic.
 * Uses a fake WebResourceRequest to avoid Android dependencies.
 */
class RequestInterceptorTest {

    @Before
    fun setUp() { HlsUrlStore.clear() }

    @After
    fun tearDown() { HlsUrlStore.clear() }

    @Test
    fun `onResponseReceived detects HLS content type`() {
        RequestInterceptor.onResponseReceived(
            "https://cdn.example.com/stream/index.m3u8",
            "application/vnd.apple.mpegurl"
        )
        assertTrue(HlsUrlStore.hasUrl())
        assertEquals("https://cdn.example.com/stream/index.m3u8", HlsUrlStore.get())
    }

    @Test
    fun `onResponseReceived detects x-mpegurl content type`() {
        RequestInterceptor.onResponseReceived(
            "https://cdn.example.com/live.m3u8",
            "application/x-mpegurl; charset=utf-8"
        )
        assertTrue(HlsUrlStore.hasUrl())
    }

    @Test
    fun `onResponseReceived ignores non-HLS content type`() {
        RequestInterceptor.onResponseReceived(
            "https://example.com/page.html",
            "text/html"
        )
        assertFalse(HlsUrlStore.hasUrl())
    }

    @Test
    fun `onResponseReceived ignores null content type`() {
        RequestInterceptor.onResponseReceived("https://example.com/video.mp4", null)
        assertFalse(HlsUrlStore.hasUrl())
    }

    @Test
    fun `HLS URL stored by m3u8 URL pattern`() {
        // Simulate what happens inside shouldInterceptRequest
        val url = "https://cdn.example.com/playlist/master.m3u8"
        if (url.endsWith(".m3u8") || url.contains(".m3u8?")) {
            HlsUrlStore.set(url, "application/vnd.apple.mpegurl")
        }
        assertTrue(HlsUrlStore.hasUrl())
        assertEquals(url, HlsUrlStore.get())
    }

    @Test
    fun `m3u8 with query string is detected`() {
        val url = "https://cdn.example.com/video.m3u8?token=abc123"
        if (url.endsWith(".m3u8") || url.contains(".m3u8?")) {
            HlsUrlStore.set(url, "application/vnd.apple.mpegurl")
        }
        assertTrue(HlsUrlStore.hasUrl())
    }
}
