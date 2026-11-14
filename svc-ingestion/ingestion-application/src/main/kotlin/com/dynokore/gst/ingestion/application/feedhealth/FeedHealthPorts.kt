package com.dynokore.gst.ingestion.application.feedhealth

import java.util.UUID

/** The controls whose state change is published as `FeedHealthChanged` (INV-REQ-815). */
enum class FeedControl { SEQUENCE, COUNT, VALUE, WATERMARK, MANIFEST }

/** The two states of a control. */
enum class FeedHealthState { FAILED, CLEARED }

/** The producer-counter key: tenant, feed, entityRef, period and control. */
data class FeedHealthKey(
    val tenantId: UUID,
    val feed: String,
    val entityRef: String,
    val period: String,
    val control: FeedControl,
)

/** One transition handed to the outbox; [stateVersion] is the producer counter value of the store. */
data class FeedHealthChange(
    val key: FeedHealthKey,
    val state: FeedHealthState,
    val stateVersion: Long,
)

/** Reads and writes the state per key in the caller's unit of work. */
interface FeedHealthStore {
    /** The stored state of [key], or null when none was stored. */
    fun current(key: FeedHealthKey): FeedHealthState?

    /** Stores [state] and returns the producer counter after the save: 1 for the first save of a key, then +1 each. */
    fun save(
        key: FeedHealthKey,
        state: FeedHealthState,
    ): Long
}

/** Hands one change to the outbox in the same unit of work as the store. */
fun interface FeedHealthEvents {
    fun publish(change: FeedHealthChange)
}
