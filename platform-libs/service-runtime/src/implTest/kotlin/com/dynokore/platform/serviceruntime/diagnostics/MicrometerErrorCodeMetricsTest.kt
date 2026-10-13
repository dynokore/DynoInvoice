package com.dynokore.platform.serviceruntime.diagnostics

import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Clock

/**
 * INV-AC-2172: "given an entity whose upstream sends documents with an
 * unmapped internal tax code; when 25 documents raise RUL-001 in one day;
 * then the RUL-001 counter for that entity and period rises by 25".
 */
class MicrometerErrorCodeMetricsTest {
    @Test
    fun `25 raises of RUL-001 for one entity and period increment that counter by 25`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerErrorCodeMetrics(registry, RecentErrorCodesRecorder(Clock.systemUTC()))

        repeat(25) { metrics.increment("RUL-001", "entity-42", "2026-09") }

        registry
            .counter(
                MicrometerErrorCodeMetrics.METER_NAME,
                "code",
                "RUL-001",
                "entity",
                "entity-42",
                "period",
                "2026-09",
            ).count() shouldBe 25.0
    }

    @Test
    fun `a different entity or period is tracked as a separate counter`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerErrorCodeMetrics(registry, RecentErrorCodesRecorder(Clock.systemUTC()))

        metrics.increment("RUL-001", "entity-42", "2026-09")
        metrics.increment("RUL-001", "entity-43", "2026-09")

        registry
            .counter(
                MicrometerErrorCodeMetrics.METER_NAME,
                "code",
                "RUL-001",
                "entity",
                "entity-42",
                "period",
                "2026-09",
            ).count() shouldBe 1.0
        registry
            .counter(
                MicrometerErrorCodeMetrics.METER_NAME,
                "code",
                "RUL-001",
                "entity",
                "entity-43",
                "period",
                "2026-09",
            ).count() shouldBe 1.0
    }

    @Test
    fun `also feeds the recent-error-codes recorder for the uniform diagnostic endpoint`() {
        val recorder = RecentErrorCodesRecorder(Clock.systemUTC())
        val metrics = MicrometerErrorCodeMetrics(SimpleMeterRegistry(), recorder)

        repeat(3) { metrics.increment("MDM-002", "entity-1", "2026-09") }

        recorder.snapshot().single { it.code == "MDM-002" }.count shouldBe 3
    }
}
