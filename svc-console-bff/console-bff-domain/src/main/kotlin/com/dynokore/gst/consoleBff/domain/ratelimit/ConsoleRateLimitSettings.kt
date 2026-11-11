package com.dynokore.gst.consoleBff.domain.ratelimit

/**
 * The console and agent protective limits per 60-second window (INV-REQ-825 b5, owner ruling INV-Q-4048 A, moved to
 * console-bff by INV-Q-4085 A): 300 requests per principal and 3,000 per tenant by default. They are installation
 * settings (`platform.engineer` in SaaS, `deployment.operator` in a private installation) and may be raised or lowered
 * because they protect capacity. [tenantOverrides] replaces either value for one tenant, keyed by the tenant id
 * exactly; a value left null keeps the installation value.
 */
data class ConsoleRateLimitSettings(
    val perPrincipal: Int = DEFAULT_PER_PRINCIPAL,
    val perTenant: Int = DEFAULT_PER_TENANT,
    val tenantOverrides: Map<String, TenantOverride> = emptyMap(),
) {
    init {
        require(perPrincipal >= 1) { AT_LEAST_ONE }
        require(perTenant >= 1) { AT_LEAST_ONE }
    }

    fun perPrincipalFor(tenant: String): Int = tenantOverrides[tenant]?.perPrincipal ?: perPrincipal

    fun perTenantFor(tenant: String): Int = tenantOverrides[tenant]?.perTenant ?: perTenant

    /** One tenant's replacement values. */
    data class TenantOverride(
        val perPrincipal: Int? = null,
        val perTenant: Int? = null,
    ) {
        init {
            listOfNotNull(perPrincipal, perTenant).forEach { require(it >= 1) { AT_LEAST_ONE } }
        }
    }

    companion object {
        const val DEFAULT_PER_PRINCIPAL = 300
        const val DEFAULT_PER_TENANT = 3_000
        private const val AT_LEAST_ONE = "a protective limit must be at least 1 request per window (INV-REQ-825)"
    }
}
