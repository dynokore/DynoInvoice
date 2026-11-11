package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.domain.ratelimit.ConsoleRateLimitSettings
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `console.rate-limit.*`: the console and agent protective limits as installation settings (INV-REQ-825 b5, API-30),
 * set by `platform.engineer` in SaaS and `deployment.operator` in a private installation through the chart values, and
 * [readyReplicas], the ready console-bff replica count the chart passes in (INV-Q-4089). A value below 1 fails the
 * start with a message naming the property (12-factor: configuration from the environment).
 */
@ConfigurationProperties("console.rate-limit")
class ConsoleRateLimitProperties(
    perPrincipal: Int?,
    perTenant: Int?,
    readyReplicas: Int?,
    tenantOverrides: Map<String, Override>?,
) {
    val perPrincipal: Int = atLeastOne("console.rate-limit.per-principal", perPrincipal)
    val perTenant: Int = atLeastOne("console.rate-limit.per-tenant", perTenant)
    val readyReplicas: Int = atLeastOne("console.rate-limit.ready-replicas", readyReplicas)
    val tenantOverrides: Map<String, Override> = tenantOverrides.orEmpty()

    /** One tenant's replacement values; a value left out keeps the installation value. */
    class Override(
        val perPrincipal: Int? = null,
        val perTenant: Int? = null,
    )

    fun settings(): ConsoleRateLimitSettings =
        ConsoleRateLimitSettings(
            perPrincipal,
            perTenant,
            tenantOverrides.mapValues { (tenant, value) ->
                val prefix = "console.rate-limit.tenant-overrides[$tenant]"
                ConsoleRateLimitSettings.TenantOverride(
                    value.perPrincipal?.let { atLeastOne("$prefix.per-principal", it) },
                    value.perTenant?.let { atLeastOne("$prefix.per-tenant", it) },
                )
            },
        )

    private companion object {
        fun atLeastOne(
            name: String,
            value: Int?,
        ): Int {
            requireNotNull(value) { "$name is required" }
            require(value >= 1) { "$name must be at least 1, was $value" }
            return value
        }
    }
}
