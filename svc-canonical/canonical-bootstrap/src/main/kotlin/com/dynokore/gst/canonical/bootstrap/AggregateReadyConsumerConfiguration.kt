package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.clients.MasterDataClient
import com.dynokore.gst.canonical.adapters.clients.MasterdataSupplierIdentity
import com.dynokore.gst.canonical.adapters.clients.RulesLineClassifier
import com.dynokore.gst.canonical.adapters.events.AggregateReadyHandler
import com.dynokore.gst.canonical.adapters.events.JacksonAggregateDocumentJson
import com.dynokore.gst.canonical.adapters.persistence.JooqEntityConstitutions
import com.dynokore.gst.canonical.adapters.persistence.JooqEntityIdentities
import com.dynokore.gst.canonical.adapters.persistence.JooqSourceEventStore
import com.dynokore.gst.canonical.application.aggregate.AggregateDocumentJson
import com.dynokore.gst.canonical.application.aggregate.AggregateEntityIds
import com.dynokore.gst.canonical.application.aggregate.AggregateSupplierIdentity
import com.dynokore.gst.canonical.application.aggregate.LineClassifier
import com.dynokore.gst.canonical.application.aggregate.RecordAggregateDocument
import com.dynokore.gst.canonical.application.document.CanonicalVersionWriter
import com.dynokore.gst.canonical.application.source.RecordSourceEvent
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.web.client.RestClient
import java.time.Clock
import java.util.UUID

/**
 * The subscription of canonical-svc to `AggregateReady` (INV-REQ-437, INV-REQ-718) as one inbox consumer on the inbox,
 * offset and dead-letter tables of schema `canonical`. The handler runs in the consumer's tenant transaction, so the
 * document, its CanonicalVersionCreated outbox row and the inbox row commit together. Like `MasterDataResolved`, the
 * consumed type is not in the emission catalogue (it holds the types canonical-svc emits).
 */
@Configuration(proxyBeanMethods = false)
class AggregateReadyConsumerConfiguration {
    @Bean
    fun aggregateEntityIds(identities: JooqEntityIdentities): AggregateEntityIds =
        AggregateEntityIds(identities::entityIdOf)

    @Bean
    fun aggregateLineClassifier(
        builder: RestClient.Builder,
        registry: ResilienceRegistry,
        environment: Environment,
    ): LineClassifier = RulesLineClassifier(builder, registry, environment)

    @Bean
    fun aggregateSupplierIdentity(
        dsl: DSLContext,
        client: MasterDataClient,
    ): AggregateSupplierIdentity = MasterdataSupplierIdentity(JooqEntityConstitutions(dsl), client)

    @Bean
    fun aggregateDocumentJson(): AggregateDocumentJson = JacksonAggregateDocumentJson()

    @Bean
    fun aggregateSourceEvents(
        dsl: DSLContext,
        transaction: TransactionRunner,
    ): RecordSourceEvent = RecordSourceEvent(JooqSourceEventStore(dsl), transaction) { UUID.randomUUID() }

    @Bean
    @Suppress("LongParameterList")
    fun recordAggregateDocument(
        sourceEvents: RecordSourceEvent,
        classifier: LineClassifier,
        entityIds: AggregateEntityIds,
        supplier: AggregateSupplierIdentity,
        json: AggregateDocumentJson,
        writer: CanonicalVersionWriter,
    ): RecordAggregateDocument = RecordAggregateDocument(sourceEvents, classifier, entityIds, supplier, json, writer)

    @Bean
    fun aggregateReadyHandler(
        record: RecordAggregateDocument,
        trace: InboundEventTrace,
    ): EventHandler = InboundTraceHandler(AggregateReadyHandler(record), trace)

    @Bean
    @Suppress("LongParameterList")
    fun aggregateReadyConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        aggregateReadyHandler: EventHandler,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            aggregateReadyHandler,
            rebuildRequired,
            clock,
        )

    companion object {
        const val CONSUMER = "canonical-svc"
        const val TOPIC = "gst.aggregation.aggregate-ready.v1"
        const val SCHEMA = "canonical"
    }
}
