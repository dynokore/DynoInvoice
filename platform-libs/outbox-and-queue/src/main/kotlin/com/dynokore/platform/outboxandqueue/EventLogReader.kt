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
 * A topic's head and purge watermark as a consumer's claim role sees them (INV-REQ-1205 b6, INV-T-3385): [head] is the
 * highest `log_offset` present (0 when the topic is empty), [watermark] the highest purged (0 when never purged).
 */
data class TopicHead(
    val head: Long,
    val watermark: Long,
) {
    /** The position past both: where a consumer moved to the head resumes. */
    val nextAfterHead: Long get() = maxOf(head, watermark) + 1
}

/** Reads a topic's [TopicHead] through the consumer's claim role (`log_offset` only). */
fun interface EventLogHead {
    fun head(topic: String): TopicHead
}
