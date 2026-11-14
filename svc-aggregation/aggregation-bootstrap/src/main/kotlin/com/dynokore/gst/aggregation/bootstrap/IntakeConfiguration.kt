package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.events.ShortCodeUsedOutbox
import com.dynokore.gst.aggregation.adapters.persistence.JooqAggregateStore
import com.dynokore.gst.aggregation.adapters.persistence.JooqHandOffStore
import com.dynokore.gst.aggregation.adapters.persistence.JooqSeenRevisions
import com.dynokore.gst.aggregation.adapters.persistence.JooqUsedShortCodes
import com.dynokore.gst.aggregation.adapters.replica.StructureCodeLookup
import com.dynokore.gst.aggregation.application.AggregateRepository
import com.dynokore.gst.aggregation.application.AggregateRevisions
import com.dynokore.gst.aggregation.application.CadenceSource
import com.dynokore.gst.aggregation.application.HandOffRepository
import com.dynokore.gst.aggregation.application.IntakeAggregate
import com.dynokore.gst.aggregation.application.PeriodBoundarySource
import com.dynokore.gst.aggregation.application.SeenRevisions
import com.dynokore.gst.aggregation.application.ShortCodes
import com.dynokore.gst.aggregation.application.WindowRepository
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** TPL-18: the intake use case and the two adapters it is composed with are constructed here and nowhere else. */
@Configuration(proxyBeanMethods = false)
class IntakeConfiguration {
    @Bean
    fun shortCodeUsedOutbox(
        outbox: OutboxWriter,
        tenants: TenantContext,
    ): ShortCodeUsedOutbox = ShortCodeUsedOutbox(outbox, tenants)

    @Bean
    fun aggregateRepository(
        dsl: DSLContext,
        runner: TransactionRunner,
        tenants: TenantContext,
        shortCodeUsed: ShortCodeUsedOutbox,
    ): JooqAggregateStore = JooqAggregateStore(dsl, runner, tenants, JooqUsedShortCodes(dsl, tenants, shortCodeUsed))

    @Bean
    fun seenRevisions(
        dsl: DSLContext,
        runner: TransactionRunner,
        tenants: TenantContext,
    ): SeenRevisions = JooqSeenRevisions(dsl, tenants, runner)

    @Bean
    fun handOffRepository(
        dsl: DSLContext,
        runner: TransactionRunner,
        tenants: TenantContext,
        store: JooqAggregateStore,
    ): HandOffRepository = JooqHandOffStore(dsl, runner, tenants, store)

    @Bean
    fun shortCodes(
        structure: StructureReplica,
        tenants: TenantContext,
    ): ShortCodes = StructureCodeLookup(structure, tenants)

    @Bean
    @Suppress("LongParameterList")
    fun intakeAggregate(
        shortCodes: ShortCodes,
        repository: AggregateRepository,
        windows: WindowRepository,
        cadences: CadenceSource,
        periods: PeriodBoundarySource,
        revisions: AggregateRevisions,
        seen: SeenRevisions,
    ): IntakeAggregate = IntakeAggregate(shortCodes, repository, windows, cadences, periods, revisions, seen)
}
