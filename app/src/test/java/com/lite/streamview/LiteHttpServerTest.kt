package com.lite.streamview

import com.lite.streamview.server.LiteHttpServer
import com.lite.streamview.store.HlsUrlStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class LiteHttpServerTest {

    private lateinit var store: HlsUrlStore
    private lateinit var server: LiteHttpServer

    @Before
    fun setUp() {
        store = HlsUrlStore()
        server = LiteHttpServer(
            port = 8089, // use non-standard test port
            hlsUrlStore = store,
            onNavigateRequested = {},
            onLiteModeChanged = {}
        )
    }

    @Test
    fun testSanitizeUrlValidHttp() {
        val url = "http://example.com/test"
        assertEquals(url, server.sanitizeUrl(url))
    }

    @Test
    fun testSanitizeUrlValidHttps() {
        val url = "https://example.com/video?stream=1"
        assertEquals(url, server.sanitizeUrl(url))
    }

    @Test
    fun testSanitizeUrlRejectsInvalidSchemes() {
        assertNull(server.sanitizeUrl("javascript:alert(1)"))
        assertNull(server.sanitizeUrl("file:///etc/passwd"))
        assertNull(server.sanitizeUrl("data:text/html,test"))
        assertNull(server.sanitizeUrl("ftp://example.com"))
        assertNull(server.sanitizeUrl("not-a-url"))
    }

    @Test
    fun testLiteModeProperty() {
        server.isLiteMode = true
        assertEquals(true, server.isLiteMode)

        server.isLiteMode = false
        assertEquals(false, server.isLiteMode)
    }
}
