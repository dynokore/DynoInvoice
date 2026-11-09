package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.AccessEventCatalogue
import com.dynokore.gst.access.adapters.events.OutboxLinkMessages
import com.dynokore.gst.access.adapters.events.SignedLinkRequestedHandler
import com.dynokore.gst.access.application.link.IssueSignedLinkUseCase
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancyjooq.TenancyJooq
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * TPL-18, INV-REQ-899: access-svc's consumer of `SignedLinkRequested` (topic
 * `gst.masterdata.signed-link-requested.v1`).
 * Like [BreakGlassReportFeed] it is an auto-configuration ordered after `L1CompositionAutoConfiguration` and after that
 * feed, so `TenantScope`, `EventLogReader` and `RebuildRequiredSink` exist when its conditions are read. The consumer
 * of the break-glass report stays the primary one for the replay wiring; this one is a second consumer on its own topic
 * (as `StructureFeed` of masterdata-svc has two).
 */
@AutoConfiguration(
    afterName = ["com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration"],
    after = [BreakGlassReportFeed::class],
)
class SignedLinkFeed {
    @Bean
    fun signedLinkRequestedHandler(
        useCase: IssueSignedLinkUseCase,
        messages: OutboxLinkMessages,
        mapper: ObjectMapper,
    ): EventHandler = SignedLinkRequestedHandler(useCase, messages, mapper, AccessEventCatalogue.signedLinkProducers())

    @Bean
    @ConditionalOnBean(EventLogReader::class, TenantScope::class, RebuildRequiredSink::class)
    @Suppress("LongParameterList")
    fun signedLinkRequestedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        @Qualifier("signedLinkRequestedHandler") handler: EventHandler,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, SignedLinkRequestedHandler.TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            TenancyJooq.dslContext(OutboxSchema.jooqTables(SCHEMA), scope.tenants, scope.transactions),
            handler,
            rebuildRequired,
            clock,
        )

    companion object {
        private const val CONSUMER = "access-svc"
        private const val SCHEMA = "access"
    }
}
