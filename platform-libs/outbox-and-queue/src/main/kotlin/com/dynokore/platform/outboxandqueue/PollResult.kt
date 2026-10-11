package com.dynokore.platform.outboxandqueue

/**
 * What one consumer poll did with the records it read. [rebuildRequired] is true when the poll read nothing because
 * the consumer is behind the topic's purge watermark ([RebuildRequired], INV-REQ-1205 b6).
 */
data class PollResult
@JvmOverloads
constructor(
    val applied: Int,
    val ignored: Int,
    val duplicates: Int,
    val stale: Int,
    val deadLettered: Int,
    val rebuildRequired: Boolean = false,
) {
    /** All records consumed by this poll. */
    val total: Int get() = applied + ignored + duplicates + stale + deadLettered
}
