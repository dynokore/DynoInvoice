package com.dynokore.platform.outboxandqueue

import java.time.Duration
import java.time.Instant

/**
 * Deletes event-log records by age, per topic (INV-REQ-1205 b4, owner ruling INV-Q-3249): a topic marked as a content
 * topic in `event-log/topics.yaml` keeps [Retention.contentWindow] (INV-ADR-2772), every other topic
 * [Retention.eventLogRetention] (INV-Q-3091); a topic marked `retained_while_resolvable` keeps
 * [Retention.packArtefactRetention] (INV-REQ-1210, INV-Q-6281). It never reads consumer positions and never waits
 * for a lagging consumer; each delete batch raises the topic's purge watermark in the same transaction
 * (INV-REQ-1205 b6), so a consumer behind it stops with [RebuildRequired] instead of skipping.
 */
interface EventLogPurge {
    /** One run over every topic; safe to repeat and to run from several workers. Deletes in batches until done. */
    fun purge(): EventLogPurgeRun
}

/** What one [EventLogPurge.purge] run did on one topic; [watermark] is the topic's watermark after it (0: none). */
data class TopicPurge(
    val topic: String,
    val contentTopic: Boolean,
    val period: Duration,
    val cutoff: Instant,
    val rows: Long,
    val watermark: Long,
    val retainedWhileResolvable: Boolean,
)

/** One [EventLogPurge.purge] run, one entry per topic. */
data class EventLogPurgeRun(
    val topics: List<TopicPurge>,
) {
    val rows: Long get() = topics.sumOf { it.rows }
}
