package com.dynokore.platform.serviceruntime.health

import com.dynokore.platform.serviceruntime.binding.GstProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import java.nio.file.Path

/**
 * Registers the `vault` readiness contributor (FR-AH.17) every service needs,
 * regardless of which domain dependencies it has: `management.endpoint.health.group
 * .readiness.include` names it alongside `readinessState`, `db` and `eventLog`
 * (TPL-16). `DomainDependencyHealthIndicator` is not registered here: a service's
 * own bootstrap constructs one per edge it actually calls, with its own probe.
 */
@AutoConfiguration
class HealthAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["vault"])
    fun vault(properties: GstProperties): VaultHealthIndicator = VaultHealthIndicator(Path.of(properties.bindings.secretStore.mountRoot))
}
