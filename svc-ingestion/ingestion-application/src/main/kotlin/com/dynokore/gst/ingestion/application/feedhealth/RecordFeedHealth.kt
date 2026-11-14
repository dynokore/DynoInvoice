package com.dynokore.gst.ingestion.application.feedhealth

/** Result of recording a control state. */
enum class FeedHealthOutcome { Published, Unchanged }

/**
 * Records a control state and publishes one change per FAILED/CLEARED transition (INV-REQ-815).
 * Internal step: the caller that evaluates the controls owns authorization. Runs in the caller's unit of work;
 * port failures propagate so that it rolls back.
 */
class RecordFeedHealth(
    private val store: FeedHealthStore,
    private val events: FeedHealthEvents,
) {
    fun record(
        key: FeedHealthKey,
        state: FeedHealthState,
    ): FeedHealthOutcome {
        val stored = store.current(key)
        val transition =
            when (state) {
                FeedHealthState.FAILED -> stored == null || stored == FeedHealthState.CLEARED
                FeedHealthState.CLEARED -> stored == FeedHealthState.FAILED
            }
        if (!transition) return FeedHealthOutcome.Unchanged
        val version = store.save(key, state)
        events.publish(FeedHealthChange(key, state, version))
        return FeedHealthOutcome.Published
    }
}
