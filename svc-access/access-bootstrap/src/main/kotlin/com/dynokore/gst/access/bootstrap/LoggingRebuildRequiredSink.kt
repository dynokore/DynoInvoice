package com.dynokore.gst.access.bootstrap

import com.dynokore.platform.outboxandqueue.RebuildRequiredIncident
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import org.slf4j.LoggerFactory

/**
 * The log-only [RebuildRequiredSink] (INV-Q-10031 A, INV-Q-10834 A, the same in every consumer service): ERROR with
 * the consumer, the topic and the offsets, nothing else. A sink supplied by the product replaces it.
 */
class LoggingRebuildRequiredSink : RebuildRequiredSink {
    override fun raise(incident: RebuildRequiredIncident) {
        LoggerFactory
            .getLogger(LoggingRebuildRequiredSink::class.java)
            .error(
                "rebuild required: consumer {} on topic {}: next offset {} is at or below purge watermark {}",
                incident.consumer,
                incident.topic,
                incident.nextOffset,
                incident.watermark,
            )
    }
}
