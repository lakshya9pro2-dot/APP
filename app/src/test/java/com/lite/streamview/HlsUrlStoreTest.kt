package com.lite.streamview

import com.lite.streamview.store.HlsStream
import com.lite.streamview.store.HlsUrlStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HlsUrlStoreTest {

    private lateinit var store: HlsUrlStore

    @Before
    fun setUp() {
        store = HlsUrlStore()
    }

    @Test
    fun testInitialStoreIsEmpty() {
        assertNull(store.getLatestHls())
    }

    @Test
    fun testSetAndRetrieveHls() {
        val stream = HlsStream("https://example.com/playlist.m3u8")
        store.setLatestHls(stream)

        val retrieved = store.getLatestHls()
        assertNotNull(retrieved)
        assertEquals("https://example.com/playlist.m3u8", retrieved?.url)
        assertEquals("application/vnd.apple.mpegurl", retrieved?.contentType)
    }

    @Test
    fun testClearStore() {
        val stream = HlsStream("https://example.com/playlist.m3u8")
        store.setLatestHls(stream)
        assertEquals("https://example.com/playlist.m3u8", store.getLatestHls()?.url)

        store.clear()
        assertNull(store.getLatestHls())
    }

    @Test
    fun testWaitForHlsTimeout() {
        // When no HLS is added, waitForHls should return null after timeout
        val result = store.waitForHls(timeoutSeconds = 1)
        assertNull(result)
    }

    @Test
    fun testWaitForHlsConcurrentDetection() {
        val latch = CountDownLatch(1)
        var receivedStream: HlsStream? = null

        thread {
            receivedStream = store.waitForHls(timeoutSeconds = 3)
            latch.countDown()
        }

        // Simulate discovery after 200ms
        Thread.sleep(200)
        store.setLatestHls(HlsStream("https://example.com/live/master.m3u8"))

        latch.await(2, TimeUnit.SECONDS)
        assertNotNull(receivedStream)
        assertEquals("https://example.com/live/master.m3u8", receivedStream?.url)
    }
}
