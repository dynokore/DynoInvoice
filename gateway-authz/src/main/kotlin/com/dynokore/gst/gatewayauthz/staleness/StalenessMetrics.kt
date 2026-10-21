package com.dynokore.gst.gatewayauthz.staleness

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry

/**
 * The metrics the staleness alert reads (owner ruling INV-Q-4021 A: the gateway alerts until the list is fresh):
 * `gateway_authz_revocation_list_age_seconds`, the age of the list (NaN before the first read), and
 * `gateway_authz_revocation_list_stale`, 1 while the list is older than the bound.
 */
object StalenessMetrics {
    const val AGE = "gateway.authz.revocation.list.age"
    const val STALE = "gateway.authz.revocation.list.stale"

    fun bind(
        registry: MeterRegistry,
        staleness: RevocationListStaleness,
    ) {
        Gauge
            .builder(AGE, staleness) { s -> s.age()?.toMillis()?.div(MILLIS_PER_SECOND) ?: Double.NaN }
            .baseUnit("seconds")
            .description("Age of the revocation list since its last complete read (INV-Q-4021 A)")
            .register(registry)
        Gauge
            .builder(STALE, staleness) { s -> if (s.isStale()) 1.0 else 0.0 }
            .description("1 while the revocation list is older than the staleness bound (INV-Q-4021 A)")
            .register(registry)
    }

    private const val MILLIS_PER_SECOND = 1000.0
}
