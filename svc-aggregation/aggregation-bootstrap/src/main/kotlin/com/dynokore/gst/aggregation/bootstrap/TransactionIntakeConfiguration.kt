package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.periods.NoPeriodBoundaries
import com.dynokore.gst.aggregation.adapters.persistence.JooqTransactionStore
import com.dynokore.gst.aggregation.application.PeriodBoundarySource
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** TPL-18: the transaction store and the production source of period boundaries (INV-Q-14444 A, INV-Q-16535 A). */
@Configuration(proxyBeanMethods = false)
class TransactionIntakeConfiguration {
    @Bean
    fun transactionStore(
        dsl: DSLContext,
        runner: TransactionRunner,
        tenants: TenantContext,
    ): JooqTransactionStore = JooqTransactionStore(dsl, runner, tenants)

    /** Until INV-T-16565 reads the real spans no span is known and a window is held, never split on a guess. */
    @Bean
    fun periodBoundarySource(): PeriodBoundarySource = NoPeriodBoundaries()
}
