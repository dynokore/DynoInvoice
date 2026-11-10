package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.persistence.JooqWindowStore
import com.dynokore.gst.aggregation.adapters.policy.PolicyCadenceSource
import com.dynokore.gst.aggregation.application.CadenceSource
import com.dynokore.gst.aggregation.application.WindowRepository
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * TPL-18: the window store and the cadence source. The [PolicyReader] is the config-resolver reader that
 * service-runtime builds over the V4 schema; VP-55 is read only through [CadenceSource].
 */
@Configuration(proxyBeanMethods = false)
class WindowConfiguration {
    @Bean
    fun windowRepository(
        dsl: DSLContext,
        runner: TransactionRunner,
        tenants: TenantContext,
    ): WindowRepository = JooqWindowStore(dsl, runner, tenants)

    @Bean
    fun cadenceSource(
        reader: PolicyReader,
        tenants: TenantContext,
        runner: TransactionRunner,
    ): CadenceSource = PolicyCadenceSource(reader, tenants, runner)
}
