package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.events.StructureNodeFeedHandler
import com.dynokore.gst.masterdata.application.counterparty.ApplyStructureNode
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.tenancyjooq.TenancyJooq
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * TPL-18, INV-Q-8427 A: the service's own consumers of the two structure events of tenancy-svc, one per topic as the
 * platform asks for several topics, both applying the node to the service's own structure table. The default
 * consumer of service-runtime backs off when an `InboxConsumer` bean exists; the first is primary for the replay
 * wiring. product-runtime keeps feeding the structure replica separately.
 */
@Configuration(proxyBeanMethods = false)
class StructureFeed {
    @Bean
    fun structureNodeFeedHandler(
        apply: ApplyStructureNode,
        mapper: ObjectMapper,
    ) = StructureNodeFeedHandler(apply, mapper)

    @Bean
    @Primary
    @ConditionalOnBean(EventLogReader::class, TenantScope::class, RebuildRequiredSink::class)
    @Suppress("LongParameterList")
    fun structureConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        handler: StructureNodeFeedHandler,
    ): JdbcInboxConsumer =
        consumer(
            ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED_TOPIC,
            catalogue,
            reader,
            scope,
            rebuildRequired,
            clock,
            handler,
        )

    @Bean
    @ConditionalOnBean(EventLogReader::class, TenantScope::class, RebuildRequiredSink::class)
    @Suppress("LongParameterList")
    fun entityProfileConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        handler: StructureNodeFeedHandler,
    ): JdbcInboxConsumer =
        consumer(
            ProductEventTypes.ENTITY_PROFILE_CHANGED_TOPIC,
            catalogue,
            reader,
            scope,
            rebuildRequired,
            clock,
            handler,
        )

    @Suppress("LongParameterList")
    private fun consumer(
        topic: String,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        handler: StructureNodeFeedHandler,
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
