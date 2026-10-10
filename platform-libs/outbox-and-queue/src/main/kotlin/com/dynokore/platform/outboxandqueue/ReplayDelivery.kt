package com.dynokore.platform.outboxandqueue

/** What the named consumer did with one replayed dead letter. */
enum class ReplayOutcome {
    APPLIED,
    IGNORED,

    /** The consumer had already applied this event id. */
    DUPLICATE,

    /** A newer `docsequence` was already held. */
    STALE,

    /** Still not a valid envelope (or not the queued tenant's): dead-lettered again, not applied. */
    REJECTED,
}

/**
 * The replay side of one named consumer: takes the items [DeadLetterTriage.replay] queued for it on the
 * replay lane and applies each through the consumer's own inbox rules, handler and transaction.
 */
interface ReplayDelivery {
    /** Delivers the next replay item; `null` when none is claimable (none queued, or the lane's limit is used up). */
    fun runOnce(): ReplayOutcome?
}
