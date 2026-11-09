package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.persistence.JooqScopePackStore
import com.dynokore.gst.masterdata.adapters.rate.ConsumedRatePackEvents
import com.dynokore.gst.masterdata.adapters.rate.PolicyElectedRateSource
import com.dynokore.gst.masterdata.adapters.rate.ReplicaApprovedRateSources
import com.dynokore.gst.masterdata.adapters.rate.RulePackActivatedHandler
import com.dynokore.gst.masterdata.adapters.rate.RulePackStagedHandler
import com.dynokore.gst.masterdata.adapters.rate.ScopePackStore
import com.dynokore.gst.masterdata.application.rate.ApprovedRateSources
import com.dynokore.gst.masterdata.application.rate.ElectedRateSource
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * TPL-18, INV-T-18775: the rate source replicas. The election is read from the policy replica through [PolicyReader];
 * the approved sources are replicated from `RulePackStaged` (packType `SCOPE`) and `RulePackActivated` into the
 * service's own tables, one inbox consumer per topic. An auto-configuration ordered after L1 (as [StructureFeed]), so
 * the `@ConditionalOnBean` guards see the event-log beans.
 */
@AutoConfiguration(afterName = ["com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration"])
class RatePackFeed {
    @Bean
    fun scopePackStore(
        dsl: DSLContext,
        mapper: ObjectMapper,
    ): ScopePackStore = JooqScopePackStore(dsl, mapper)

    @Bean
    fun electedRateSource(
        policy: PolicyReader,
        tenants: TenantContext,
        tx: TransactionRunner,
        mapper: ObjectMapper,
    ): ElectedRateSource = PolicyElectedRateSource(policy, tenants, tx, mapper)

    @Bean
    fun approvedRateSources(
        packs: ScopePackStore,
        tx: TransactionRunner,
    ): ApprovedRateSources = ReplicaApprovedRateSources(packs, tx)

    @Bean
    fun rulePackStagedHandler(
        packs: ScopePackStore,
        mapper: ObjectMapper,
    ) = RulePackStagedHandler(packs, mapper)

    @Bean
    fun rulePackActivatedHandler(
        packs: ScopePackStore,
        mapper: ObjectMapper,
    ) = RulePackActivatedHandler(packs, mapper)

    @Bean
    @ConditionalOnBean(EventLogReader::class, TenantScope::class, RebuildRequiredSink::class)
    @Suppress("LongParameterList")
    fun rulePackStagedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        rulePackStagedHandler: RulePackStagedHandler,
    ): JdbcInboxConsumer =
        consumer(
            ConsumedRatePackEvents.RULE_PACK_STAGED_TOPIC,
            catalogue,
            reader,
            scope,
            rebuildRequired,
            clock,
            rulePackStagedHandler,
        )

    @Bean
    @ConditionalOnBean(EventLogReader::class, TenantScope::class, RebuildRequiredSink::class)
    @Suppress("LongParameterList")
    fun rulePackActivatedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        rulePackActivatedHandler: RulePackActivatedHandler,
    ): JdbcInboxConsumer =
        consumer(
            ConsumedRatePackEvents.RULE_PACK_ACTIVATED_TOPIC,
            catalogue,
            reader,
            scope,
            rebuildRequired,
            clock,
            rulePackActivatedHandler,
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
