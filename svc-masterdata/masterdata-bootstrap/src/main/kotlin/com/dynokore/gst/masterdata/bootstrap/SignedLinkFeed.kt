package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.events.ConsumedSignedLinkEvents
import com.dynokore.gst.masterdata.adapters.events.SignedLinkInvalidatedHandler
import com.dynokore.gst.masterdata.adapters.events.SignedLinkIssuedHandler
import com.dynokore.gst.masterdata.adapters.persistence.JooqLinkBindings
import com.dynokore.gst.masterdata.application.request.CounterpartyRequests
import com.dynokore.gst.masterdata.application.request.SubmitCounterpartyDetails
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
 * TPL-18, INV-T-18221: the link binding consumers. `SignedLinkIssued` and `SignedLinkInvalidated` of access-svc are
 * applied by one inbox consumer per topic (at-least-once, idempotent and order-insensitive); a payload that breaks the
 * contract is a contract violation and goes to dead-letter triage, never dropped. An auto-configuration ordered after
 * L1 (as [StructureFeed] and [RatePackFeed]), so the `@ConditionalOnBean` guards see the event-log beans.
 */
@AutoConfiguration(afterName = ["com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration"])
class SignedLinkFeed {
    @Bean
    fun signedLinkIssuedHandler(
        tx: TransactionRunner,
        submit: SubmitCounterpartyDetails,
        requests: CounterpartyRequests,
        bindings: JooqLinkBindings,
        mapper: ObjectMapper,
    ) = SignedLinkIssuedHandler(tx, submit, requests, bindings, mapper)

    @Bean
    fun signedLinkInvalidatedHandler(
        tx: TransactionRunner,
        submit: SubmitCounterpartyDetails,
        bindings: JooqLinkBindings,
        mapper: ObjectMapper,
    ) = SignedLinkInvalidatedHandler(tx, submit, bindings, mapper)

    @Bean
    @ConditionalOnBean(EventLogReader::class, TenantScope::class, RebuildRequiredSink::class)
    @Suppress("LongParameterList")
    fun signedLinkIssuedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        signedLinkIssuedHandler: SignedLinkIssuedHandler,
    ): JdbcInboxConsumer =
        consumer(
            ConsumedSignedLinkEvents.SIGNED_LINK_ISSUED_TOPIC,
            catalogue,
            reader,
            scope,
            rebuildRequired,
            clock,
            signedLinkIssuedHandler,
        )

    @Bean
    @ConditionalOnBean(EventLogReader::class, TenantScope::class, RebuildRequiredSink::class)
    @Suppress("LongParameterList")
    fun signedLinkInvalidatedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        signedLinkInvalidatedHandler: SignedLinkInvalidatedHandler,
    ): JdbcInboxConsumer =
        consumer(
            ConsumedSignedLinkEvents.SIGNED_LINK_INVALIDATED_TOPIC,
            catalogue,
            reader,
            scope,
            rebuildRequired,
            clock,
            signedLinkInvalidatedHandler,
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
