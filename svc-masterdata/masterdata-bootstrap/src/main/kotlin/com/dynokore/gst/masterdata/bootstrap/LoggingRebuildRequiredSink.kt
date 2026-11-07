package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.platform.outboxandqueue.RebuildRequiredIncident
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink

/**
 * The default [RebuildRequiredSink] (INV-Q-10834 A, INV-Q-16993 A): one ERROR line per incident holding the consumer,
 * topic, next offset and watermark, so a rebuild-required stop is never silent (NIST SP 800-92). Delivery to a person
 * is open for every consumer service and is not built here.
 */
class LoggingRebuildRequiredSink : RebuildRequiredSink {
    override fun raise(incident: RebuildRequiredIncident) {
        LOG.log(
            System.Logger.Level.ERROR,
            "rebuild required: consumer={0} topic={1} nextOffset={2} watermark={3}",
            incident.consumer,
            incident.topic,
            incident.nextOffset,
            incident.watermark,
        )
    }

    private companion object {
        private val LOG = System.getLogger(LoggingRebuildRequiredSink::class.java.name)
    }
}
