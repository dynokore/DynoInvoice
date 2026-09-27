package com.dynokore.platform.serviceruntime.health

/**
 * Checks whether one domain dependency (another service's synchronous edge,
 * SDS §2.5) is currently reachable — for the readiness-group reporting of
 * FR-AH.17: unavailable means this service reports *degraded*, never failed
 * (`ingestion-svc` returning `503` while `canonical-svc` is down, FR-AH.17
 * behaviour #2). Framework-free (ARC-16): a service's own adapter implements
 * this against its real client; [DomainDependencyHealthIndicator]
 * is the Spring Boot `HealthIndicator` that wraps it.
 */
fun interface DomainDependencyProbe {
    /** `true` when the dependency answered; `false` reports `DEGRADED`, never `DOWN`. */
    fun healthy(): Boolean
}
