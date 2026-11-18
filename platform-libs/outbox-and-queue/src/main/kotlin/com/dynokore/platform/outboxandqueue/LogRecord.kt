package com.dynokore.platform.outboxandqueue

/** One record read from a topic: its position in the topic and the envelope JSON as stored. */
data class LogRecord(
    val offset: Long,
    val envelopeJson: String,
) {
    /** True for a replayed dead letter, which has no position in the topic. */
    val replayed: Boolean get() = offset == REPLAY_OFFSET

    companion object {
        /** The offset of a replayed record: it has no position in the topic. */
        const val REPLAY_OFFSET = 0L
    }
}
