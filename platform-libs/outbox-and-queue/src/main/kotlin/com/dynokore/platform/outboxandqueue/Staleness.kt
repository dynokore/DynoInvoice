package com.dynokore.platform.outboxandqueue

import java.time.Duration
import java.time.Instant

/**
 * The staleness threshold of one consumer for one event type (INV-REQ-1165). There is no default: the source fixes
 * no figure (INV-Q-2134), so the service supplies each from the event matrix; an installation may only tighten it.
 */
data class StalenessRule(
    val consumer: String,
    val eventType: String,
    val threshold: Duration,
) {
    init {
        require(!threshold.isZero && !threshold.isNegative) { "threshold must be positive" }
    }
}

/**
 * The measured staleness of one consumer of one event type: the metric. [lag] is zero with nothing unconsumed.
 * [backlog] is the offset lag (the topic's head minus the consumer's position, coordinator ruling INV-Q-4264): an upper
 * bound of the records unconsumed, since offsets of rolled-back inserts and of purged rows count.
 */
data class ConsumerStaleness(
    val consumer: String,
    val eventType: String,
    val backlog: Long,
    val oldestUnconsumed: Instant?,
    val lag: Duration,
    val threshold: Duration,
) {
    val breached: Boolean get() = lag > threshold
}

/** A consumer has left an event unconsumed past its threshold (INV-AC-2150). */
data class StalenessBreach(
    val consumer: String,
    val eventType: String,
    val lag: Duration,
    val threshold: Duration,
    val detectedAt: Instant,
)

/** Where a breach goes: the service raises the incident or emits its event. Called once per breach. */
fun interface StalenessBreachSink {
    fun raise(breach: StalenessBreach)
}

/** Measures every rule and raises a breach when a lag passes its threshold. */
interface StalenessMonitor {
    /** Measures each rule now; returns the metrics and raises each newly breached rule once until it recovers. */
    fun check(): List<ConsumerStaleness>
}

/** The event log's view of what is unconsumed on a topic, through `eventlog.claim` (INV-Q-4264). */
interface EventLogLag {
    /**
     * The offset lag after [afterOffset] (an upper bound of the records unconsumed) and the insertion time of the
     * oldest.
     */
    fun backlog(
        topic: String,
        afterOffset: Long,
    ): LogBacklog
}

data class LogBacklog(
    val count: Long,
    val oldestInsertedAt: Instant?,
)
