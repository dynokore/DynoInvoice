package com.dynokore.platform.serviceruntime.composition

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
    ): TransactionRunnerImpl = TransactionRunnerImpl(TenantAwareDataSource(dataSource), tenants)

    companion object {
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
