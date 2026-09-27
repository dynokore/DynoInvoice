package com.dynokore.platform.serviceruntime.health

/**
 * The current outbox lag: rows written but not yet relayed, for the uniform
 * diagnostic endpoint (FR-AE.82). Framework-free port; `outbox-and-queue`
 * (no code yet — settings.gradle.kts) supplies the real implementation once it
 * has an outbox to measure. Absent a bound implementation, the diagnostic
 * endpoint reports this depth as unavailable rather than failing.
 */
fun interface OutboxDepthSource {
    /** Rows currently outstanding in the outbox. */
    fun depth(): Long
}

/**
 * The current queue lag: claimed-but-unfinished work items, for the uniform
 * diagnostic endpoint (FR-AE.82). Framework-free port; `outbox-and-queue`
 * supplies the real implementation once it has a queue to measure.
 */
fun interface QueueDepthSource {
    /** Items currently outstanding in the queue. */
    fun depth(): Long
}
