package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.serviceruntime.pool.LaneConnection
import com.dynokore.platform.serviceruntime.pool.LanedDataSource
import com.dynokore.platform.tenancykernel.ConnectionLabel
import com.dynokore.platform.tenancykernel.LicenceCheck
import com.dynokore.platform.tenancykernel.NonBlockingLicenceCheck
import com.dynokore.platform.tenancykernel.TenantAwareDataSource
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import javax.sql.DataSource

/**
 * tenancy-kernel's ports (INV-T-2875, INV-Q-3710), when the service embeds its impl half: the [TenantContext]
 * (also the `PlatformTenantBinding`), and over the service's application `DataSource` the [TransactionRunner]
 * (also the `CurrentConnection` of its transaction), on a [TenantAwareDataSource] so a pooled connection never
 * keeps a tenant. The base [LicenceCheck] is tenancy-svc's (INV-REQ-1076, INV-Q-3732): when the service declares
 * one, it is wrapped in [NonBlockingLicenceCheck].
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["com.dynokore.platform.tenancykernel.TransactionRunnerImpl"])
class TenancyKernelWiring {
    @Bean
    @ConditionalOnMissingBean(TenantContext::class)
    fun tenantContext(): TenantContextImpl = TenantContextImpl()

    @Bean
    @ConditionalOnBean(DataSource::class)
    @ConditionalOnMissingBean(TransactionRunner::class)
    fun transactionRunner(
        dataSource: DataSource,
        tenants: TenantContext,
        environment: Environment,
    ): TransactionRunnerImpl =
        TransactionRunnerImpl(
            TenantAwareDataSource(dataSource),
            tenants,
            // each transaction names its pool lane, transaction-locally (INV-T-3982, coordinator ruling INV-Q-4487)
            environment.getProperty("gst.service")?.takeIf { it.isNotBlank() }?.let { laneLabel(it) },
        )

    companion object {
        /**
         * The transaction runner's label (coordinator ruling INV-Q-4487): `<service>:<lane>` for a connection taken from
         * the laned application pool ([LanedDataSource]), set transaction-locally in tenancy-kernel's binding statement;
         * `null` (statement unchanged) for any other connection.
         */
        fun laneLabel(service: String): ConnectionLabel =
            ConnectionLabel { connection ->
                if (connection.isWrapperFor(LaneConnection::class.java)) {
                    "$service:${connection.unwrap(LaneConnection::class.java).lane.name.lowercase()}"
                } else {
                    null
                }
            }

        /** Wraps the service's base [LicenceCheck] (never one already wrapped) in [NonBlockingLicenceCheck]. */
        @Bean
        @JvmStatic
        fun nonBlockingLicenceCheck(): BeanPostProcessor = LicenceCheckWrapper()
    }
}

internal class LicenceCheckWrapper : BeanPostProcessor {
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any = if (bean is LicenceCheck && bean !is NonBlockingLicenceCheck) NonBlockingLicenceCheck(bean) else bean
}
