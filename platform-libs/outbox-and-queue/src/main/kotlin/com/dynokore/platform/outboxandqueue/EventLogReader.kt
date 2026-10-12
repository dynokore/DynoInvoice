package com.dynokore.platform.outboxandqueue

/** A consumer's read side of the event log (INV-REQ-1205): SELECT on the topics it subscribes to. */
interface EventLogReader {
    /**
     * Up to [limit] records of [topic] with an offset greater than [afterOffset], in offset order.
     *
     * @throws RebuildRequired when `afterOffset + 1` is at or below the topic's purge watermark: records the caller
     *   has not consumed were purged, so nothing is returned (INV-REQ-1205 b6).
     */
    fun read(
        topic: String,
        afterOffset: Long,
        limit: Int,
    ): List<LogRecord>
}

/**
 * A topic's head and purge watermark as a consumer's runtime login sees them through `eventlog.claim`
 * (INV-REQ-1205 b6, coordinator ruling INV-Q-4264): [head] is the highest `log_offset` present at or after the
 * search's start (the start itself when nothing is present after it, 0 for an empty topic searched from 0),
 * [watermark] the highest purged (0 when never purged).
 */
data class TopicHead(
    val head: Long,
    val watermark: Long,
) {
    /** The position past both: where a consumer moved to the head resumes. */
    val nextAfterHead: Long get() = maxOf(head, watermark) + 1
}

/**
 * Reads a topic's [TopicHead] through `eventlog.claim` only: a galloping search of single-row claims from [head]'s
 * `from`, the consumer's committed position (coordinator ruling INV-Q-4264).
 */
fun interface EventLogHead {
    fun head(
        topic: String,
        from: Long,
    ): TopicHead

    /** [head] searched from the topic's start (or its purge watermark). */
    fun head(topic: String): TopicHead = head(topic, 0)
}
