package com.dynokore.platform.serviceruntime.health

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator

/**
 * The reusable readiness contributor for one domain dependency (FR-AH.17): a
 * service's own bootstrap registers one of these per synchronous edge it calls,
 * named for a `management.endpoint.health.group.dependencies.include` entry
 * (TPL-16: `dependencies.include: transportEdge`). Structurally never `DOWN` —
 * [probe] failing reports [DegradedStatus.DEGRADED] instead, regardless of what
 * [probe] does internally, the same structural guarantee
 * `tenancy-kernel`'s `NonBlockingLicenceCheck` gives its own port.
 */
class DomainDependencyHealthIndicator(
    private val dependencyName: String,
    private val probe: DomainDependencyProbe,
) : HealthIndicator {
    override fun health(): Health =
        if (probe.healthy()) {
            Health.up().withDetail("dependency", dependencyName).build()
        } else {
            Health.status(DegradedStatus.DEGRADED).withDetail("dependency", dependencyName).build()
        }
}
