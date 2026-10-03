package com.liteweb.extractor.engine

/** Result of one extraction job. */
data class ExtractResult(
    val hlsUrl: String?,
    val contentType: String?,
    val error: String?,
    val elapsedMs: Long,
    /** True when every worker was busy for too long (HTTP 503 instead of "not found"). */
    val busy: Boolean = false
)

/** Something that can turn a page URL into an HLS URL. Blocking; call from worker threads. */
interface Extractor {
    fun extract(pageUrl: String, timeoutMs: Long): ExtractResult
    fun poolSize(): Int
    fun activeJobs(): Int
}
