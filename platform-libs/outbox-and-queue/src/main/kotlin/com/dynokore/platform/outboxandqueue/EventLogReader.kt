package com.dynokore.platform.outboxandqueue

/** A consumer's read side of the event log (INV-REQ-1205): SELECT on the topics it subscribes to. */
interface EventLogReader {
    /** Up to [limit] records of [topic] with an offset greater than [afterOffset], in offset order. */
    fun read(
        topic: String,
        afterOffset: Long,
        limit: Int,
    ): List<LogRecord>
}
