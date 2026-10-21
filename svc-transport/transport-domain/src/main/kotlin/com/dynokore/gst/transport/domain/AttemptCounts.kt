package com.dynokore.gst.transport.domain

/** Attempts of one adapter in a window, counted from the attempt log. */
data class AttemptCounts(
    val total: Long,
    val failed: Long,
) {
    init {
        require(total >= 0 && failed in 0..total) { "failed ($failed) is within total ($total)" }
    }

    /** failed divided by total; zero when the window holds no attempt. */
    val failureRate: Double get() = if (total == 0L) 0.0 else failed.toDouble() / total.toDouble()
}
