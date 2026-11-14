package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.http.DrillDownCursors
import com.dynokore.gst.aggregation.adapters.persistence.JooqAggregateStore
import com.dynokore.gst.aggregation.adapters.persistence.JooqDrillDownReader
import com.dynokore.gst.aggregation.adapters.persistence.JooqReplacedReceiptStore
import com.dynokore.gst.aggregation.adapters.persistence.JooqTransactionStore
import com.dynokore.gst.aggregation.application.Atomically
import com.dynokore.gst.aggregation.application.DrillDownReader
import com.dynokore.gst.aggregation.application.ExcludeReplacedReceipt
import com.dynokore.gst.aggregation.application.GetAggregateDrillDown
import com.dynokore.gst.aggregation.application.ReplacedReceiptStore
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** TPL-18: the transaction store (the source of period boundaries is PeriodSpansConfiguration). */
@Configuration(proxyBeanMethods = false)
class TransactionIntakeConfiguration {
    @Bean
    fun transactionStore(
        dsl: DSLContext,
        runner: TransactionRunner,
        tenants: TenantContext,
    ): JooqTransactionStore = JooqTransactionStore(dsl, runner, tenants)

    @Bean
    fun replacedReceiptStore(
        dsl: DSLContext,
        runner: TransactionRunner,
        tenants: TenantContext,
        aggregates: JooqAggregateStore,
    ): ReplacedReceiptStore = JooqReplacedReceiptStore(dsl, runner, tenants, aggregates)

    @Bean
    fun excludeReplacedReceipt(
        store: ReplacedReceiptStore,
        runner: TransactionRunner,
    ): ExcludeReplacedReceipt =
        ExcludeReplacedReceipt(
            store,
            object : Atomically {
                override fun <T> run(block: () -> T): T = runner.inTransaction { block() }
            },
        )

    @Bean
    fun drillDownReader(
        dsl: DSLContext,
        runner: TransactionRunner,
        tenants: TenantContext,
    ): DrillDownReader = JooqDrillDownReader(dsl, runner, tenants)

    @Bean
    fun getAggregateDrillDown(reader: DrillDownReader): GetAggregateDrillDown = GetAggregateDrillDown(reader)

    @Bean
    fun drillDownCursors(
        secrets: SecretStore,
        clock: Clock,
    ): DrillDownCursors = DrillDownCursors(secrets, clock)
}
