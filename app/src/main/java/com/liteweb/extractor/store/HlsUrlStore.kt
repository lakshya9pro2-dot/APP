package com.liteweb.extractor.store

import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe store for the latest detected HLS URL.
 * Uses AtomicReference to avoid heavy synchronization.
 */
object HlsUrlStore {

    private val latestUrl = AtomicReference<String?>(null)
    private val latestContentType = AtomicReference<String?>(null)

    /** Save a newly detected HLS URL */
    fun set(url: String, contentType: String? = null) {
        latestUrl.set(url)
        latestContentType.set(contentType)
    }

    /** Get the latest detected HLS URL, or null if none found */
    fun get(): String? = latestUrl.get()

    /** Get the content type of the latest HLS URL */
    fun getContentType(): String? = latestContentType.get()

    /** Clear stored values (e.g., when loading a new page) */
    fun clear() {
        latestUrl.set(null)
        latestContentType.set(null)
    }

    /** True if an HLS URL has been detected */
    fun hasUrl(): Boolean = latestUrl.get() != null
}
