package com.dynokore.platform.outboxandqueue

import java.time.Clock

/**
 * Raises the 'rebuild required' incident of one consumer topic once, until the topic resumes ([JdbcInboxConsumer]):
 * the poll that finds the consumer behind the purge watermark ([RebuildRequired]) stops the topic, the first poll
 * that can read again resumes it.
 */
internal class RebuildStop(
    private val consumer: String,
    private val topic: String,
    private val sink: RebuildRequiredSink,
    private val clock: Clock,
) {
    private var stopped = false

    fun raise(behind: RebuildRequired) {
        if (!stopped) {
            sink.raise(
                RebuildRequiredIncident(
                    consumer,
                    topic,
                    behind.nextOffset,
                    behind.watermark,
                    clock.instant(),
                ),
            )
            stopped = true
        }
    }

    fun resume() {
        stopped = false
    }
}
