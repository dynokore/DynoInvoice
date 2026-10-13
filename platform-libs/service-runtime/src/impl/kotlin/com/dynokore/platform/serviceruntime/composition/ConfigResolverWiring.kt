package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.configresolver.BackdatingFloor
import com.dynokore.platform.configresolver.ConfigPurge
import com.dynokore.platform.configresolver.DelegationProbe
import com.dynokore.platform.configresolver.DeploymentForm
import com.dynokore.platform.configresolver.JdbcConfigPurge
import com.dynokore.platform.configresolver.JdbcPolicyHistory
import com.dynokore.platform.configresolver.JdbcPolicyReader
import com.dynokore.platform.configresolver.JdbcPolicyWriter
import com.dynokore.platform.configresolver.JdbcResolutionRecorder
import com.dynokore.platform.configresolver.PolicyHistory
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.configresolver.ResolutionRecorder
import com.dynokore.platform.configresolver.ValueConstraints
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.binding.GstProperties
import com.dynokore.platform.tenancykernel.CurrentConnection
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
 * schema and its [VariationPointCatalogue], for the deployment form `gst.form`. The policy writer uses the
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
    @ConditionalOnMissingBean(PolicyReader::class)
    fun policyReader(
        connections: CurrentConnection,
        catalogue: VariationPointCatalogue,
        gst: GstProperties,
        modules: ModuleProperties,
    ): JdbcPolicyReader = JdbcPolicyReader(connections, catalogue, gst.configForm(), modules.requireSchema())

    @Bean
    @ConditionalOnMissingBean(PolicyHistory::class)
    fun policyHistory(
        connections: CurrentConnection,
        catalogue: VariationPointCatalogue,
        gst: GstProperties,
        modules: ModuleProperties,
    ): JdbcPolicyHistory = JdbcPolicyHistory(connections, catalogue, gst.configForm(), modules.requireSchema())

    @Bean
    @ConditionalOnMissingBean(ConfigPurge::class)
    fun configPurge(
        connections: CurrentConnection,
        modules: ModuleProperties,
    ): JdbcConfigPurge = JdbcConfigPurge(connections, modules.requireSchema())

    @Bean
    @ConditionalOnMissingBean(ResolutionRecorder::class)
    fun resolutionRecorder(
        connections: CurrentConnection,
        modules: ModuleProperties,
        clock: Clock,
    ): JdbcResolutionRecorder = JdbcResolutionRecorder(connections, modules.requireSchema(), clock)

    @Bean
    @ConditionalOnBean(AuditWriter::class)
    @ConditionalOnMissingBean(PolicyWriter::class)
    @Suppress("LongParameterList")
    fun policyWriter(
        connections: CurrentConnection,
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
            connections,
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

    private fun GstProperties.configForm(): DeploymentForm = DeploymentForm.valueOf(form.name)
}
