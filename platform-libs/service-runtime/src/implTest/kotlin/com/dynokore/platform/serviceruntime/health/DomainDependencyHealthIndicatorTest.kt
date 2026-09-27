package com.dynokore.platform.serviceruntime.health

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status

/**
 * FR-AH.17 behaviour #2: "ingestion-svc returning 503 while canonical-svc is
 * down" — a domain dependency being unreachable is DEGRADED, structurally never
 * DOWN, regardless of what the probe itself does.
 */
class DomainDependencyHealthIndicatorTest {
    @Test
    fun `reports UP when the dependency is healthy`() {
        val indicator = DomainDependencyHealthIndicator("canonicalSvc", DomainDependencyProbe { true })
        indicator.health().status shouldBe Status.UP
    }

    @Test
    fun `reports DEGRADED, never DOWN, when the dependency is unreachable`() {
        val indicator = DomainDependencyHealthIndicator("canonicalSvc", DomainDependencyProbe { false })
        indicator.health().status shouldBe DegradedStatus.DEGRADED
    }

    @Test
    fun `the dependency name is carried as a detail, for the diagnostic response`() {
        val indicator = DomainDependencyHealthIndicator("canonicalSvc", DomainDependencyProbe { false })
        indicator.health().details["dependency"] shouldBe "canonicalSvc"
    }
}
