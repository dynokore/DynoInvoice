package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.events.MasterDataResolvedHandler
import com.dynokore.gst.canonical.adapters.events.OutboxReleasedEventPublisher
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentHoldStore
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentReleaseStore
import com.dynokore.gst.canonical.application.document.ReleaseMasterDataHold
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.RebuildRequiredIncident
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The subscription of canonical-svc to `MasterDataResolved` (INV-REQ-479, rounds zq and zs) as one inbox consumer on
 * the inbox, offset and dead-letter tables of schema `canonical` (V1). The handler runs in the consumer's tenant
 * transaction, so the release, the DocumentReleased outbox row and the inbox row commit together.
 */
@Configuration(proxyBeanMethods = false)
class MasterDataResolvedConsumerConfiguration {
    @Bean
    fun documentHoldStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): JooqDocumentHoldStore = JooqDocumentHoldStore(dsl, tenants)

    @Bean
    fun documentReleaseStore(
        holds: JooqDocumentHoldStore,
        dsl: DSLContext,
        tenants: TenantContext,
    ): JooqDocumentReleaseStore = JooqDocumentReleaseStore(holds, dsl, tenants)

    @Bean
    fun inboundEventTrace(): InboundEventTrace = InboundEventTrace()

    @Bean
    fun outboxReleasedEventPublisher(
        outbox: OutboxWriter,
        tenants: TenantContext,
        trace: InboundEventTrace,
    ): OutboxReleasedEventPublisher =
        OutboxReleasedEventPublisher(outbox, tenants, { trace.correlation() }, { trace.causation() })

    @Bean
    fun releaseMasterDataHold(
        store: JooqDocumentReleaseStore,
        publisher: OutboxReleasedEventPublisher,
        transaction: TransactionRunner,
    ): ReleaseMasterDataHold = ReleaseMasterDataHold(store, publisher, transaction)

    /** Until the product binds an incident sink, a consumer that needs a rebuild is logged at ERROR. */
    @Bean
    @ConditionalOnMissingBean(RebuildRequiredSink::class)
    fun rebuildRequiredSink(): RebuildRequiredSink = LoggingRebuildRequiredSink()

    @Bean
    fun masterDataResolvedHandler(
        release: ReleaseMasterDataHold,
        trace: InboundEventTrace,
    ): EventHandler = InboundTraceHandler(MasterDataResolvedHandler(release), trace)

    @Bean
    @Suppress("LongParameterList")
    fun masterDataResolvedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        masterDataResolvedHandler: EventHandler,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            masterDataResolvedHandler,
            rebuildRequired,
            clock,
        )

    companion object {
        const val CONSUMER = "canonical-svc"
        const val TOPIC = "gst.masterdata.master-data-resolved.v1"
        const val SCHEMA = "canonical"
    }
}

/**
 * The event being handled on this thread. The consumer runs a handler synchronously on the polling thread, so the
 * events the release stages carry the incoming event's correlation id and have its id as their causation (FR-K.3).
 * Reading outside a handled event fails closed.
 */
class InboundEventTrace {
    private val current = ThreadLocal<Envelope>()

    fun <T> within(
        event: Envelope,
        body: () -> T,
    ): T {
        current.set(event)
        try {
            return body()
        } finally {
            current.remove()
        }
    }

    fun correlation(): CorrelationId = CorrelationId(active().correlation.value)

    fun causation(): CausationId = CausationId(active().id.value)

    private fun active(): Envelope = checkNotNull(current.get()) { "no inbound event is being handled" }
}

/** Runs [delegate] with the event recorded in the [InboundEventTrace]; the outcome and any exception pass through. */
class InboundTraceHandler(
    private val delegate: EventHandler,
    private val trace: InboundEventTrace,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome = trace.within(event) { delegate.handle(event) }
}

/** The log-only [RebuildRequiredSink]: ERROR with the consumer, the topic and the offsets, nothing else. */
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
