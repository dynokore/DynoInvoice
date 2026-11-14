package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.persistence.JooqAggregateStore
import com.dynokore.gst.aggregation.adapters.replica.StructureCodeLookup
import com.dynokore.gst.aggregation.application.AggregateRepository
import com.dynokore.gst.aggregation.application.AggregateRevisions
import com.dynokore.gst.aggregation.application.CadenceSource
import com.dynokore.gst.aggregation.application.IntakeAggregate
import com.dynokore.gst.aggregation.application.PeriodBoundarySource
import com.dynokore.gst.aggregation.application.ShortCodes
import com.dynokore.gst.aggregation.application.WindowRepository
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
    fun aggregateRepository(
        dsl: DSLContext,
        runner: TransactionRunner,
        tenants: TenantContext,
    ): JooqAggregateStore = JooqAggregateStore(dsl, runner, tenants)

    @Bean
    fun shortCodes(
        structure: StructureReplica,
        tenants: TenantContext,
    ): ShortCodes = StructureCodeLookup(structure, tenants)

    @Bean
    fun intakeAggregate(
        shortCodes: ShortCodes,
        repository: AggregateRepository,
        windows: WindowRepository,
        cadences: CadenceSource,
        periods: PeriodBoundarySource,
        revisions: AggregateRevisions,
    ): IntakeAggregate = IntakeAggregate(shortCodes, repository, windows, cadences, periods, revisions)
}
