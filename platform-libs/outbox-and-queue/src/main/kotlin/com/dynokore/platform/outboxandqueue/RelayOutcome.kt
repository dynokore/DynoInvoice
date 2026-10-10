package com.dynokore.platform.outboxandqueue

/** What one relay run did: rows published, rows still unpublished afterwards, and whether the binding stopped it. */
data class RelayOutcome(
    val published: Int,
    val pending: Long,
    val stalled: Boolean,
)
