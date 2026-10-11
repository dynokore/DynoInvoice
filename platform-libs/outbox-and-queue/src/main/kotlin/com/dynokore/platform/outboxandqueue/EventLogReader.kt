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
