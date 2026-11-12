package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.events.SubmissionAcknowledgedHandler
import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.application.chain.AcknowledgementRecorder
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The subscription of evidence-svc to `gst.submission.submission-acknowledged.v1` (INV-REQ-691): one inbox consumer on
 * the inbox, offset and dead-letter tables of schema `evidence`, with [SubmissionAcknowledgedHandler] over the
 * [AcknowledgementRecorder] built on the jOOQ stores. The topic is an ordinary one (not a record topic), so the
 * consumer keeps the `docsequence` check, as `evidenceArtefactConsumer` does. The handler runs in the consumer's
 * transaction (the runtime may wrap the handler bean in a tracing decorator, so the consumer takes it as an
 * [EventHandler] by name), so the locator, the record, the inbox row and the offset commit together (INV-REQ-1222).
 * The consumer fails construction when the catalogue lacks the entry of the topic with the event type below
 * (fail closed).
 */
@Configuration(proxyBeanMethods = false)
class AcknowledgementConsumerConfiguration {
    @Bean
    fun submissionAcknowledgedHandler(
        dsl: DSLContext,
        clock: Clock,
    ): SubmissionAcknowledgedHandler =
        SubmissionAcknowledgedHandler(ChainWiring.acknowledgementRecorder(dsl, clock))

    @Bean
    @Suppress("LongParameterList")
    fun submissionAcknowledgedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        @Qualifier("submissionAcknowledgedHandler") handler: EventHandler,
    ): JdbcInboxConsumer {
        val entry = catalogue.topic(TOPIC)
        check(entry != null && entry.eventType == EVENT_TYPE) {
            "the event catalogue has no $EVENT_TYPE entry on $TOPIC"
        }
        return JdbcInboxConsumer(SUBSCRIPTION, catalogue, reader, scope, dsl, handler, rebuildRequired, clock)
    }

    companion object {
        const val TOPIC = "gst.submission.submission-acknowledged.v1"
        const val EVENT_TYPE = "com.dynokore.gst.submission.SubmissionAcknowledged.v1"
        const val PRODUCER = "submission-svc"

        val SUBSCRIPTION = ConsumerSubscription(LocatorConsumerConfiguration.CONSUMER, TOPIC, ChainWiring.SCHEMA)
    }
}
