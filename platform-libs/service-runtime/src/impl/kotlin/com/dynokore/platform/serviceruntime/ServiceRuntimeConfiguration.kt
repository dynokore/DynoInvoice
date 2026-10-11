package com.dynokore.platform.serviceruntime

import com.dynokore.platform.serviceruntime.binding.BindingAutoConfiguration
import com.dynokore.platform.serviceruntime.client.InternalRestClientAutoConfiguration
import com.dynokore.platform.serviceruntime.composition.AdapterRuntimeWiring
import com.dynokore.platform.serviceruntime.composition.AuditLogWiring
import com.dynokore.platform.serviceruntime.composition.ConfigResolverWiring
import com.dynokore.platform.serviceruntime.composition.CryptoWiring
import com.dynokore.platform.serviceruntime.composition.EvidenceStoreWiring
import com.dynokore.platform.serviceruntime.composition.ModuleProperties
import com.dynokore.platform.serviceruntime.composition.OutboxAndQueueWiring
import com.dynokore.platform.serviceruntime.composition.RulepackKernelWiring
import com.dynokore.platform.serviceruntime.composition.ServiceRuntimeWiring
import com.dynokore.platform.serviceruntime.composition.TenancyKernelWiring
import com.dynokore.platform.serviceruntime.diagnostics.DiagnosticsAutoConfiguration
import com.dynokore.platform.serviceruntime.health.HealthAutoConfiguration
import com.dynokore.platform.serviceruntime.http.JobCommandHttpAutoConfiguration
import com.dynokore.platform.serviceruntime.identity.EndUserIdentityAutoConfiguration
import com.dynokore.platform.serviceruntime.identity.IdentityAutoConfiguration
import com.dynokore.platform.serviceruntime.logging.LoggingAutoConfiguration
import com.dynokore.platform.serviceruntime.replica.ReplicaAutoConfiguration
import com.dynokore.platform.serviceruntime.resilience.ResilienceAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.PropertySource

/**
 * This module's composition root (TPL-18): "L1 composition root: bindings of
 * §12.1, TransactionRunner, replicas" — this module's own share of that is the
 * bindings, health, diagnostics and the structure, authority and policy replicas
 * (INV-REQ-1199). `TransactionRunner` is `tenancy-kernel`'s own.
 *
 * A Spring Boot `@AutoConfiguration`, so it activates on the classpath alone
 * (`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`)
 * — but every service's own `bootstrap` module still names it explicitly, the way
 * TPL-18's own template does:
 * ```
 * @SpringBootApplication(scanBasePackages = [...])
 * @Import(ServiceRuntimeConfiguration::class, ProductRuntimeConfiguration::class)
 * class SubmissionApplication
 * ```
 * so a service's own composition stays legible from its `bootstrap` module
 * without needing to know this module registers itself automatically too.
 *
 * It is also the L1 composition root (INV-MOD-2371, INV-T-2875, INV-Q-3710): `composition/` wires each L1 module
 * the service embeds, and only those (@ConditionalOnClass on the module's impl half), every port with a
 * platform-libs implementation becoming a bean the service can override (@ConditionalOnMissingBean). An
 * implementation that needs inputs only the service has (its role DataSources, topics catalogue, retention, lane
 * limits, staleness rules, an S3Client) is created only when the service declares them; the README lists each
 * port and its inputs. The service declares those beans in its own `bootstrap` configuration, which Spring
 * registers before this one. The modules are wired in dependency order, so each wiring sees the beans of the
 * modules below it.
 */
@AutoConfiguration(
    afterName = [
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
    ],
)
@EnableConfigurationProperties(ModuleProperties::class)
@Import(
    BindingAutoConfiguration::class,
    HealthAutoConfiguration::class,
    DiagnosticsAutoConfiguration::class,
    LoggingAutoConfiguration::class,
    ResilienceAutoConfiguration::class,
    IdentityAutoConfiguration::class,
    InternalRestClientAutoConfiguration::class,
    TenancyKernelWiring::class,
    OutboxAndQueueWiring::class,
    AuditLogWiring::class,
    ConfigResolverWiring::class,
    EvidenceStoreWiring::class,
    AdapterRuntimeWiring::class,
    CryptoWiring::class,
    RulepackKernelWiring::class,
    ServiceRuntimeWiring::class,
    ReplicaAutoConfiguration::class,
    EndUserIdentityAutoConfiguration::class,
    JobCommandHttpAutoConfiguration::class,
)
@PropertySource("classpath:service-runtime-defaults.properties")
class ServiceRuntimeConfiguration
