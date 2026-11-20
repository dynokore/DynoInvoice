package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.events.AcknowledgementReplica
import com.dynokore.gst.canonical.adapters.events.SubmissionAcknowledgedInbox
import com.dynokore.gst.canonical.adapters.persistence.JooqSubmissionAcknowledgements
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * INV-T-25749: the subscription of canonical-svc to `SubmissionAcknowledged` as one inbox consumer feeding the
 * canonical-local acknowledgement replica (database per service, event-carried state transfer).
 */
@Configuration(proxyBeanMethods = false)
class AcknowledgementReplicaConfiguration {
    @Bean
    fun submissionAcknowledgedHandler(
        dsl: DSLContext,
        clock: Clock,
        trace: InboundEventTrace,
    ): EventHandler {
        val store = JooqSubmissionAcknowledgements(dsl, clock)
        return InboundTraceHandler(
            SubmissionAcknowledgedInbox(
                AcknowledgementReplica { tenant, doc, version, id, at -> store.record(tenant, doc, version, id, at) },
            ),
            trace,
        )
    }

    @Bean
    @Suppress("LongParameterList")
    fun submissionAcknowledgedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        submissionAcknowledgedHandler: EventHandler,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            submissionAcknowledgedHandler,
            rebuildRequired,
            clock,
        )

    companion object {
        const val CONSUMER = "canonical-svc-submission-acknowledged"
        const val TOPIC = "gst.submission.submission-acknowledged.v1"
        const val EVENT_TYPE = "com.dynokore.gst.submission.SubmissionAcknowledged.v1"
        const val SCHEMA = "canonical"
    }
}
