package com.dynokore.platform.outboxandqueue

/** One record read from a topic: its position in the topic and the envelope JSON as stored. */
data class LogRecord(
    val offset: Long,
    val envelopeJson: String,
)
