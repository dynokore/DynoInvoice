package com.dynokore.gst.gatewayauthz.staleness

import com.dynokore.gst.gatewayauthz.refresh.KeyMaterialCache
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator

/**
 * Readiness (coordinator ruling INV-Q-4008 A, INV-ADR-4051 item 4): DOWN until the verification keys and one full
 * revocation-list snapshot are loaded, so the pod takes no traffic; UP afterwards, also while refreshes fail (the
 * cache is served and the staleness bound applies, owner ruling INV-Q-4021 A).
 */
class KeyMaterialReadiness(
    private val cache: KeyMaterialCache,
) : HealthIndicator {
    override fun health(): Health =
        if (cache.loaded) {
            Health.up().build()
        } else {
            Health.down().withDetail("reason", NOT_LOADED).build()
        }

    private companion object {
        const val NOT_LOADED = "verification keys and a revocation-list snapshot not loaded yet"
    }
}
