package com.dynokore.platform.outboxandqueue

import java.time.Instant

/**
 * Thrown by an [EventLogReader] when the records a consumer still needs on [topic] have been purged (INV-REQ-1205 b6,
 * INV-Q-3249): its next position, [nextOffset] (committed offset plus one), is at or below the topic's purge
 * [watermark], the highest `log_offset` the purge has removed. Nothing is returned, so the consumer cannot skip
 * silently to the next surviving record; it stops that topic until the recovery path (the producers' replay by scope
 * or a full sync, INV-REQ-1205 b4) has rebuilt its read model and moved its position past the watermark.
 */
class RebuildRequired(
    val topic: String,
    val nextOffset: Long,
    val watermark: Long,
) : RuntimeException("rebuild required on $topic: next offset $nextOffset is at or below purge watermark $watermark")

/** The 'rebuild required' incident of one consumer on one topic (INV-REQ-1205 b6), alertable like staleness. */
data class RebuildRequiredIncident(
    val consumer: String,
    val topic: String,
    val nextOffset: Long,
    val watermark: Long,
    val detectedAt: Instant,
)

/**
 * The close of a 'rebuild required' incident (INV-REQ-1205 b6, INV-Q-3644, INV-AC-3658): the consumer applied the
 * ReplayCompleted marker of [replayId] (on an 'every service' replay topic, a marker from each source it has seen,
 * INV-Q-3669), so the replay that rebuilds [topic] is complete.
 */
data class RebuildRequiredClosed(
    val consumer: String,
    val topic: String,
    val replayId: java.util.UUID,
    val closedAt: Instant,
)

/**
 * Where a 'rebuild required' incident goes: the service raises the incident (INV-REQ-1165's alert path). [raise] is
 * called once when a consumer stops a topic, and again only after it has resumed and stopped once more; [close] once
 * per topic of a replay when that replay completes, and only then (at least once: after a crash before it is recorded
 * as sent it is called again).
 */
fun interface RebuildRequiredSink {
    fun raise(incident: RebuildRequiredIncident)

    fun close(closed: RebuildRequiredClosed) {}
}
