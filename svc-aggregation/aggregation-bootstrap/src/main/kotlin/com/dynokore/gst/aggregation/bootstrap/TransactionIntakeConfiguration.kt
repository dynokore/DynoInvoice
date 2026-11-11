package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.persistence.JooqTransactionStore
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** TPL-18: the transaction store (the source of period boundaries is PeriodSpansConfiguration). */
@Configuration(proxyBeanMethods = false)
class TransactionIntakeConfiguration {
    @Bean
    fun transactionStore(
        dsl: DSLContext,
        runner: TransactionRunner,
        tenants: TenantContext,
    ): JooqTransactionStore = JooqTransactionStore(dsl, runner, tenants)
}
