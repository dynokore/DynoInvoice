package com.dynokore.platform.outboxandqueue

/** What one consumer poll did with the records it read. */
data class PollResult(
    val applied: Int,
    val ignored: Int,
    val duplicates: Int,
    val stale: Int,
    val deadLettered: Int,
) {
    /** All records consumed by this poll. */
    val total: Int get() = applied + ignored + duplicates + stale + deadLettered
}
