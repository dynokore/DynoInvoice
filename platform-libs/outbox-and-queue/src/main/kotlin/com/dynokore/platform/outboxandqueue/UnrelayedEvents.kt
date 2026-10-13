package com.dynokore.platform.outboxandqueue

import java.time.Duration
import java.time.Instant

/**
 * An outbox row marked published whose event is not in its topic in the event log (owner ruling INV-Q-4334 (B)):
 * the residual risk of INV-ADR-3968's consequences, an unrelayed row marked published by the runtime login.
 */
data class UnrelayedEvent(
    val service: String,
    val topic: String,
    val eventId: String,
    val outboxSeq: Long,
    val publishedAt: Instant,
)

/** Where a finding goes: the service raises the alert. Called once per row while the row stays a finding. */
fun interface UnrelayedEventSink {
    fun raise(finding: UnrelayedEvent)
}

/** The result of one check: the findings open after it (the gauge's value is their count). */
data class UnrelayedEventCheckResult(
    val open: List<UnrelayedEvent>,
)

/** Compares the service's published outbox rows with the event log and raises each new finding once. */
interface UnrelayedEventCheck {
    /** Checks now; returns the open findings and raises each new one once. */
    fun check(): UnrelayedEventCheckResult
}

/**
 * The event log's answer to "which of these ids are not in this topic", through `eventlog.events_missing` only (owner
 * ruling INV-Q-4350 (A)): never content, and only for a topic the caller produces.
 */
interface EventLogPresence {
    /** Of [eventIds] (at most [MAX_IDS]), those not in [topic], and the topic's retention cut-off. */
    fun missing(
        topic: String,
        eventIds: Collection<String>,
    ): MissingEvents

    companion object {
        const val MAX_IDS = 1_000
    }
}

/**
 * [missing] ids of a call, and the topic's retention [cutoff] in the event log's time: an event inserted before it may
 * have been purged.
 */
data class MissingEvents(
    val missing: Set<String>,
    val cutoff: Instant,
)

/**
 * The settings of the check. [margin]: only rows published more than [margin] after the event log's cut-off are
 * reported, so an event purged at the cut-off is never reported although its row was marked published a little after
 * its insert (relay lag, and clock skew when the event log is on another instance). No default: the value awaits
 * INV-Q-4403.
 */
data class UnrelayedEventSettings(
    val margin: Duration,
    val batchSize: Int = DEFAULT_BATCH,
) {
    init {
        require(!margin.isZero && !margin.isNegative) { "margin must be positive" }
        require(batchSize in 1..EventLogPresence.MAX_IDS) {
            "batch size must be 1..${EventLogPresence.MAX_IDS}, was $batchSize"
        }
    }

    companion object {
        const val DEFAULT_BATCH = 500
    }
}
