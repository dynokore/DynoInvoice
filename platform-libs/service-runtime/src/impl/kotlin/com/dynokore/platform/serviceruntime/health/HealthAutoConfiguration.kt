package com.dynokore.platform.serviceruntime.health

import com.dynokore.platform.serviceruntime.binding.GstProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import java.nio.file.Path

/**
 * Registers the `vault` readiness contributor (FR-AH.17) every service needs,
 * regardless of which domain dependencies it has: `management.endpoint.health.group
 * .ready.include` names it alongside `readinessState`, `db` and `eventLog`
 * (TPL-16). `DomainDependencyHealthIndicator` is not registered here: a service's
 * own bootstrap constructs one per edge it actually calls, with its own probe.
 * [ManagementPortGuard] keeps health, metrics and diagnostics off the application port (API-33, INV-Q-4103);
 * [HealthGroupKeyGuard] refuses the former `liveness` and `readiness` group keys (INV-Q-4192).
 */
@AutoConfiguration
class HealthAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["vault"])
    fun vault(properties: GstProperties): VaultHealthIndicator =
        VaultHealthIndicator(Path.of(properties.bindings.secretStore.mountRoot))

    @Bean
    fun managementPortGuard(environment: Environment): ManagementPortGuard = ManagementPortGuard(environment)

    @Bean
    fun healthGroupKeyGuard(environment: Environment): HealthGroupKeyGuard = HealthGroupKeyGuard(environment)
}
