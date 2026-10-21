package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.serviceruntime.binding.InstallationBindingKey
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.pool.LaneGateHolder
import com.dynokore.platform.serviceruntime.pool.PoolLanes
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.ReplicaBoundEntities
import com.dynokore.platform.tenancykernel.ConnectionLabel
import com.dynokore.platform.tenancykernel.LicenceCheck
import com.dynokore.platform.tenancykernel.NonBlockingLicenceCheck
import com.dynokore.platform.tenancykernel.TenantAwareDataSource
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.springframework.beans.factory.ObjectProvider
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
 * one, it is wrapped in [NonBlockingLicenceCheck]. When the service has an [AuthorityReplica], each transaction also
 * binds the entity set the replica holds for the tenant and actor (INV-REQ-866, INV-AC-1912; INV-T-7520).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["com.dynokore.platform.tenancykernel.TransactionRunnerImpl"])
class TenancyKernelWiring {
    @Bean
    @ConditionalOnMissingBean(TenantContext::class)
    fun tenantContext(): TenantContextImpl = TenantContextImpl()

    /**
     * The binding key read through [SecretStore] (INV-T-5360); start-up fails when the current secret is missing
     * (INV-T-4156). The key exists only where the application has a `DataSource`, as its one consumer, the
     * [TransactionRunner], does: a service without a database needs no tenancy secret (INV-T-7937).
     */
    @Bean
    @ConditionalOnBean(DataSource::class)
    @ConditionalOnMissingBean(InstallationBindingKey::class)
    fun installationBindingKey(secrets: ObjectProvider<SecretStore>): InstallationBindingKey =
        InstallationBindingKey.of(secrets.getIfAvailable())

    @Bean
    @ConditionalOnBean(DataSource::class)
    @ConditionalOnMissingBean(TransactionRunner::class)
    fun transactionRunner(
        dataSource: DataSource,
        tenants: TenantContext,
        environment: Environment,
        gate: ObjectProvider<LaneGateHolder>,
        bindingKey: InstallationBindingKey,
        authority: ObjectProvider<AuthorityReplica>,
    ): TransactionRunnerImpl {
        val lanes = gate.getIfAvailable()?.takeIf { it.active }
        return TransactionRunnerImpl(
            TenantAwareDataSource(dataSource, lanes),
            tenants,
            // each transaction on the laned application pool names its lane, transaction-locally (INV-T-3982,
            // coordinator ruling INV-Q-4487)
            if (lanes ==
                null
            ) {
                null
            } else {
                environment.getProperty("gst.service")?.takeIf { it.isNotBlank() }?.let { laneLabel(it) }
            },
            bindingKey.source,
            // the entity set of the acting principal, from the authority replica when the service has one (INV-T-7520)
            entities = authority.getIfAvailable()?.let { ReplicaBoundEntities(it) },
        )
    }

    companion object {
        /**
         * The transaction runner's label (coordinator ruling INV-Q-4487): `<service>:<lane>` of the calling thread's
         * lane, read from [PoolLanes.current] (coordinator ruling INV-Q-4506), set transaction-locally in
         * tenancy-kernel's binding statement.
         */
        fun laneLabel(service: String): ConnectionLabel =
            ConnectionLabel { "$service:${PoolLanes.current().name.lowercase()}" }

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
