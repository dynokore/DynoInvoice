package com.dynokore.platform.serviceruntime.health

import org.springframework.boot.health.contributor.Status

/**
 * FR-AH.17: a domain dependency being unavailable is *degraded*, never failed —
 * a status distinct from the built-in [Status.DOWN], the same custom named
 * status TPL-16's `application.yaml` fragment adds to
 * `management.endpoint.health.status.order` and maps to HTTP `200`
 * (`status.http-mapping: { DEGRADED: 200 }`). Every
 * [DomainDependencyHealthIndicator] reports this status, never `DOWN`, so a
 * dependant service like `ingestion-svc` degrades instead of failing when
 * `canonical-svc` is down (FR-AH.17 behaviour #2).
 */
object DegradedStatus {
    val DEGRADED: Status = Status("DEGRADED", "a domain dependency is unavailable; this service serves what it can")
}
