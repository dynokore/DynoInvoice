package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.events.ConsumedHeldDocumentEvents
import com.dynokore.gst.masterdata.adapters.events.DocumentHeldHandler
import com.dynokore.gst.masterdata.adapters.events.DocumentReleasedHandler
import com.dynokore.gst.masterdata.application.held.HeldDocuments
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * INV-T-21336: the held-documents replica consumers. `DocumentHeld` and `DocumentReleased` of canonical-svc are
 * applied by one inbox consumer per topic (at-least-once, idempotent and order-insensitive by the per-document
 * sequence); a payload that breaks the contract goes to dead-letter triage, never dropped. An auto-configuration
 * ordered after L1 (as [SignedLinkFeed]), so the `@ConditionalOnBean` guards see the event-log beans.
 */
@AutoConfiguration(afterName = ["com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration"])
class HeldDocumentFeed {
    @Bean
    fun documentHeldHandler(
        tx: TransactionRunner,
        heldDocuments: HeldDocuments,
        mapper: ObjectMapper,
    ) = DocumentHeldHandler(tx, heldDocuments, mapper)

    @Bean
    fun documentReleasedHandler(
        tx: TransactionRunner,
        heldDocuments: HeldDocuments,
        mapper: ObjectMapper,
    ) = DocumentReleasedHandler(tx, heldDocuments, mapper)

    @Bean
    @ConditionalOnBean(EventLogReader::class, TenantScope::class, RebuildRequiredSink::class)
    @Suppress("LongParameterList")
    fun documentHeldConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        documentHeldHandler: DocumentHeldHandler,
    ): JdbcInboxConsumer =
        consumer(
            ConsumedHeldDocumentEvents.DOCUMENT_HELD_TOPIC,
            catalogue,
            reader,
            scope,
            rebuildRequired,
            clock,
            documentHeldHandler,
        )

    @Bean
    @ConditionalOnBean(EventLogReader::class, TenantScope::class, RebuildRequiredSink::class)
    @Suppress("LongParameterList")
    fun documentReleasedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        documentReleasedHandler: DocumentReleasedHandler,
    ): JdbcInboxConsumer =
        consumer(
            ConsumedHeldDocumentEvents.DOCUMENT_RELEASED_TOPIC,
            catalogue,
            reader,
            scope,
            rebuildRequired,
            clock,
            documentReleasedHandler,
        )

    @Suppress("LongParameterList")
    private fun consumer(
        topic: String,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        handler: EventHandler,
    ) = JdbcInboxConsumer(
        ConsumerSubscription("masterdata-svc", topic, SCHEMA),
        catalogue,
        reader,
        scope,
        TenancyJooq.dslContext(OutboxSchema.jooqTables(SCHEMA), scope.tenants, scope.transactions),
        handler,
        rebuildRequired,
        clock,
    )

    private companion object {
        const val SCHEMA = "masterdata"
    }
}
