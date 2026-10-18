package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.configresolver.BackdatingFloor
import com.dynokore.platform.configresolver.ConfigPurge
import com.dynokore.platform.configresolver.ConfigTables
import com.dynokore.platform.configresolver.DelegationProbe
import com.dynokore.platform.configresolver.DeploymentForm
import com.dynokore.platform.configresolver.JdbcConfigPurge
import com.dynokore.platform.configresolver.JdbcOptionValueMaximum
import com.dynokore.platform.configresolver.JdbcPolicyHistory
import com.dynokore.platform.configresolver.JdbcPolicyReader
import com.dynokore.platform.configresolver.JdbcPolicyWriter
import com.dynokore.platform.configresolver.JdbcResolutionRecorder
import com.dynokore.platform.configresolver.OptionValueMaximum
import com.dynokore.platform.configresolver.PolicyHistory
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.configresolver.ResolutionRecorder
import com.dynokore.platform.configresolver.ValueConstraints
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.binding.GstProperties
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * config-resolver's ports (INV-T-2875, INV-Q-3710), when the service embeds its impl half: over the service's
 * schema, each on the module's DSLContext of the [TransactionRunner] (INV-T-4517, ruling INV-Q-5275) and its [VariationPointCatalogue], for the deployment form `gst.form`. The policy writer uses the
 * service's [DelegationProbe], [BackdatingFloor] and [ValueConstraints] when it declares them, and its outbox
 * when it has one.
 *
 * Imported by [L1CompositionAutoConfiguration], which is ordered after product-runtime's `ProductRuntimeConfiguration`,
 * so the catalogue is registered before the condition below is evaluated (INV-T-3881, ruling INV-Q-3877).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["com.dynokore.platform.configresolver.JdbcPolicyReader"])
@ConditionalOnBean(CurrentConnection::class, VariationPointCatalogue::class)
@ConditionalOnProperty(name = [Props.SCHEMA])
class ConfigResolverWiring {
    @Bean
    @ConditionalOnBean(TransactionRunner::class)
    @ConditionalOnMissingBean(PolicyReader::class)
    fun policyReader(
        transactionRunner: TransactionRunner,
        tenants: TenantContext,
        catalogue: VariationPointCatalogue,
        gst: GstProperties,
        modules: ModuleProperties,
    ): JdbcPolicyReader =
        JdbcPolicyReader(
            moduleDsl(transactionRunner, tenants, modules),
            catalogue,
            gst.configForm(),
            modules.requireSchema(),
        )

    @Bean
    @ConditionalOnBean(TransactionRunner::class)
    @ConditionalOnMissingBean(PolicyHistory::class)
    fun policyHistory(
        transactionRunner: TransactionRunner,
        tenants: TenantContext,
        catalogue: VariationPointCatalogue,
        gst: GstProperties,
        modules: ModuleProperties,
    ): JdbcPolicyHistory =
        JdbcPolicyHistory(
            moduleDsl(transactionRunner, tenants, modules),
            catalogue,
            gst.configForm(),
            modules.requireSchema(),
        )

    @Bean
    @ConditionalOnBean(TransactionRunner::class)
    @ConditionalOnMissingBean(ConfigPurge::class)
    fun configPurge(
        transactionRunner: TransactionRunner,
        tenants: TenantContext,
        modules: ModuleProperties,
    ): JdbcConfigPurge = JdbcConfigPurge(moduleDsl(transactionRunner, tenants, modules), modules.requireSchema())

    @Bean
    @ConditionalOnBean(TransactionRunner::class)
    @ConditionalOnMissingBean(OptionValueMaximum::class)
    fun optionValueMaximum(
        transactionRunner: TransactionRunner,
        tenants: TenantContext,
        modules: ModuleProperties,
    ): JdbcOptionValueMaximum =
        JdbcOptionValueMaximum(moduleDsl(transactionRunner, tenants, modules), modules.requireSchema())

    @Bean
    @ConditionalOnBean(TransactionRunner::class)
    @ConditionalOnMissingBean(ResolutionRecorder::class)
    fun resolutionRecorder(
        transactionRunner: TransactionRunner,
        tenants: TenantContext,
        modules: ModuleProperties,
        clock: Clock,
    ): JdbcResolutionRecorder =
        JdbcResolutionRecorder(moduleDsl(transactionRunner, tenants, modules), modules.requireSchema(), clock)

    @Bean
    @ConditionalOnBean(TransactionRunner::class, AuditWriter::class)
    @ConditionalOnMissingBean(PolicyWriter::class)
    @Suppress("LongParameterList")
    fun policyWriter(
        transactionRunner: TransactionRunner,
        tenants: TenantContext,
        catalogue: VariationPointCatalogue,
        gst: GstProperties,
        modules: ModuleProperties,
        audit: AuditWriter,
        outbox: ObjectProvider<OutboxWriter>,
        delegation: ObjectProvider<DelegationProbe>,
        backdating: ObjectProvider<BackdatingFloor>,
        constraints: ObjectProvider<ValueConstraints>,
        clock: Clock,
    ): JdbcPolicyWriter =
        JdbcPolicyWriter(
            moduleDsl(transactionRunner, tenants, modules),
            catalogue,
            gst.configForm(),
            modules.requireSchema(),
            audit,
            outbox.ifAvailable,
            clock,
            delegation.ifAvailable,
            backdating.ifAvailable,
            constraints.ifAvailable,
        )

    /**
     * config-resolver's DSLContext (INV-T-5303; coordinator ruling INV-Q-5275): tenancy-jooq's factory over the
     * [TransactionRunner], so the lane gate of its TenantAwareDataSource still holds, with the module's tables
     * declared by [ConfigTables]. Not a bean: the context keeps its one DSLContext (TenancyJooqWiring).
     */
    private fun moduleDsl(
        transactionRunner: TransactionRunner,
        tenants: TenantContext,
        modules: ModuleProperties,
    ): DSLContext = TenancyJooq.dslContext(ConfigTables.declared(modules.requireSchema()), tenants, transactionRunner)

    private fun GstProperties.configForm(): DeploymentForm = DeploymentForm.valueOf(form.name)
}
