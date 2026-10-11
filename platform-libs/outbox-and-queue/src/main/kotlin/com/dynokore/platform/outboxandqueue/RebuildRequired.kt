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
 * Where a 'rebuild required' incident goes: the service raises the incident (INV-REQ-1165's alert path). Called once
 * when a consumer stops a topic, and again only after it has resumed and stopped once more.
 */
fun interface RebuildRequiredSink {
    fun raise(incident: RebuildRequiredIncident)
}
