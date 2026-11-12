package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.events.LoggingSealSignals
import com.dynokore.gst.evidence.adapters.events.PayloadReadyHandler
import com.dynokore.gst.evidence.adapters.events.SubmissionDespatchedHandler
import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqHeldPayloadStore
import com.dynokore.gst.evidence.adapters.persistence.JooqParkedDespatchStore
import com.dynokore.gst.evidence.adapters.persistence.JooqPayloadQuarantineStore
import com.dynokore.gst.evidence.adapters.persistence.JooqSealedPayloadSink
import com.dynokore.gst.evidence.application.payload.SealSignals
import com.dynokore.gst.evidence.application.payload.TransmittedPayloadSealer
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
 * The subscriptions of evidence-svc to `gst.payload.payload-ready.v1` and `gst.submission.submission-despatched.v1`
 * (INV-REQ-645): one inbox consumer each on the inbox, offset and dead-letter tables of schema `evidence`, both
 * over one [TransmittedPayloadSealer] built on the jOOQ stores. Both topics are ordinary ones, so the consumers keep
 * the `docsequence` check. A handler runs in its consumer's transaction, so the held payload, the record, the
 * `sealed_payload` link, the inbox row and the offset commit together (INV-REQ-1222). A consumer fails construction
 * when the catalogue lacks the entry of its topic with the event type below (fail closed).
 */
@Configuration(proxyBeanMethods = false)
class SealingConsumerConfiguration {
    @Bean
    fun sealSignals(): SealSignals = LoggingSealSignals()

    @Bean
    fun transmittedPayloadSealer(
        dsl: DSLContext,
        clock: Clock,
        signals: SealSignals,
    ): TransmittedPayloadSealer =
        TransmittedPayloadSealer(
            JooqHeldPayloadStore(dsl),
            JooqParkedDespatchStore(dsl, clock),
            JooqPayloadQuarantineStore(dsl),
            JooqSealedPayloadSink(dsl, ChainWiring.recordAppender(dsl, clock)),
            signals,
            clock,
        )

    @Bean
    fun payloadReadyHandler(sealer: TransmittedPayloadSealer): PayloadReadyHandler = PayloadReadyHandler(sealer)

    @Bean
    fun submissionDespatchedHandler(sealer: TransmittedPayloadSealer): SubmissionDespatchedHandler =
        SubmissionDespatchedHandler(sealer)

    @Bean
    @Suppress("LongParameterList")
    fun payloadReadyConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        @Qualifier("payloadReadyHandler") handler: EventHandler,
    ): JdbcInboxConsumer =
        consumer(
            PAYLOAD_READY_SUBSCRIPTION,
            PAYLOAD_READY_TYPE,
            catalogue,
            reader,
            scope,
            rebuildRequired,
            clock,
            dsl,
            handler,
        )

    @Bean
    @Suppress("LongParameterList")
    fun submissionDespatchedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        @Qualifier("submissionDespatchedHandler") handler: EventHandler,
    ): JdbcInboxConsumer =
        consumer(
            DESPATCHED_SUBSCRIPTION,
            DESPATCHED_TYPE,
            catalogue,
            reader,
            scope,
            rebuildRequired,
            clock,
            dsl,
            handler,
        )

    @Suppress("LongParameterList")
    private fun consumer(
        subscription: ConsumerSubscription,
        eventType: String,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        handler: EventHandler,
    ): JdbcInboxConsumer {
        val entry = catalogue.topic(subscription.topic)
        check(entry != null && entry.eventType == eventType) {
            "the event catalogue has no $eventType entry on ${subscription.topic}"
        }
        return JdbcInboxConsumer(subscription, catalogue, reader, scope, dsl, handler, rebuildRequired, clock)
    }

    companion object {
        const val PAYLOAD_READY_TOPIC = "gst.payload.payload-ready.v1"
        const val PAYLOAD_READY_TYPE = "com.dynokore.gst.payload.PayloadReady.v1"
        const val PAYLOAD_READY_PRODUCER = "payload-svc"
        const val DESPATCHED_TOPIC = "gst.submission.submission-despatched.v1"
        const val DESPATCHED_TYPE = "com.dynokore.gst.submission.SubmissionDespatched.v1"
        const val DESPATCHED_PRODUCER = "submission-svc"

        val PAYLOAD_READY_SUBSCRIPTION =
            ConsumerSubscription(LocatorConsumerConfiguration.CONSUMER, PAYLOAD_READY_TOPIC, ChainWiring.SCHEMA)
        val DESPATCHED_SUBSCRIPTION =
            ConsumerSubscription(LocatorConsumerConfiguration.CONSUMER, DESPATCHED_TOPIC, ChainWiring.SCHEMA)
    }
}
