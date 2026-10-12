package com.dynokore.platform.serviceruntime.health

import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.context.properties.source.ConfigurationPropertyName
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource
import org.springframework.core.env.Environment

/**
 * Coordinator ruling INV-Q-4192 (API-33, FR-AH.17): the health groups are `live` and `ready`, served at /health/live
 * and /health/ready on the management port. A service still configuring the former `liveness` or `readiness` group
 * (TPL-16 before service-runtime 4.0.0) would silently create a separate group and leave its db or eventLog out of
 * /health/ready, so start-up fails when any property source sets a key under either former group, in any relaxed
 * form (properties, YAML, environment variables).
 */
class HealthGroupKeyGuard(
    private val environment: Environment,
) : InitializingBean {
    override fun afterPropertiesSet() {
        val former =
            ConfigurationPropertySources
                .get(environment)
                .filterIsInstance<IterableConfigurationPropertySource>()
                .flatMap { source -> source.filter { name -> FORMER_GROUPS.any { it.isAncestorOf(name) } } }
                .map(ConfigurationPropertyName::toString)
                .distinct()
                .sorted()
        check(former.isEmpty()) {
            "Health groups are named live and ready (/health/live, /health/ready; service-runtime 4.0.0, INV-Q-4192): " +
                "rename management.endpoint.health.group.liveness.* to group.live.* and group.readiness.* to " +
                "group.ready.*; found $former"
        }
    }

    private companion object {
        val FORMER_GROUPS =
            listOf("liveness", "readiness").map { ConfigurationPropertyName.of("management.endpoint.health.group.$it") }
    }
}
