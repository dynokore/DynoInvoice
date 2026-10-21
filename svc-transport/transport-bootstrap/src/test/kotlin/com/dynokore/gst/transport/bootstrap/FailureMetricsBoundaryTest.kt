package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TransportOutcome
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * The failure metrics of the adapters, counted from the attempt log (INV-REQ-565 behaviour 3). Queue depth and
 * oldest-unsent age are exposed by submission-svc, which owns the queue, and are not asserted here.
 */
class FailureMetricsBoundaryTest {
    @BeforeEach
    fun resetSimulator() {
        ApSimulator.reset()
    }

    private fun Harness.seed(
        tenant: UUID,
        outcomes: List<TransportOutcome>,
    ) = outcomes.forEach { outcome ->
        scope.bind(
            com.dynokore.platform.tenancykernel
                .TenantId(tenant),
            "svc:transport-svc",
        ) {
            val attempt = attempts.begin(EntityRef("E-001"), DocRef(UUID.randomUUID(), 1), primary, Instant.now())
            attempts.complete(attempt, outcome, Instant.now())
        }
    }

    private fun MeterRegistry.value(
        name: String,
        adapter: String,
    ): Double = get(name).tag("adapter", adapter).gauge().value()

    private val rejected =
        TransportOutcome.Rejected(
            listOf(
                com.dynokore.gst.transport.domain
                    .FailureReason("SUB-003", null, "x"),
            ),
        )

    @Test
    fun `INV-AC-1766 one failed attempt out of four is reported as a failure rate of 25 percent`(
        @TempDir dir: Path,
    ) = Harness.run(dir, "transport.ap.sandbox-endpoint=${ApSimulator.baseUrl}") { h ->
        val ok = TransportOutcome.Accepted("ack")
        h.seed(UUID.randomUUID(), listOf(ok, ok, ok, TransportOutcome.TransientFailure))
        val registry = h.context.getBean(MeterRegistry::class.java)
        registry.value("transport.ap.failure.rate", h.primary.value) shouldBe 0.25
        registry.value("transport.ap.attempts.window", h.primary.value) shouldBe 4.0
    }

    @Test
    fun `INV-AC-7855 the failure rate equals failed over attempts counted from the log across tenants`(
        @TempDir dir: Path,
    ) = Harness.run(dir, "transport.ap.sandbox-endpoint=${ApSimulator.baseUrl}") { h ->
        h.seed(UUID.randomUUID(), listOf(TransportOutcome.Accepted("a"), TransportOutcome.TransientFailure))
        h.seed(UUID.randomUUID(), listOf(TransportOutcome.Accepted("b"), rejected, TransportOutcome.Accepted("c")))
        val counts = h.attempts.counts(h.primary, Instant.now().minusSeconds(3600))
        counts.total shouldBe 5L
        counts.failed shouldBe 2L
        val registry = h.context.getBean(MeterRegistry::class.java)
        registry.value("transport.ap.failure.rate", h.primary.value) shouldBe
            counts.failed.toDouble() / counts.total.toDouble()
        registry.value("transport.ap.failed.attempts.window", h.primary.value) shouldBe 2.0
        // another adapter has no attempt in the window
        registry.value("transport.ap.failure.rate", h.secondary.value) shouldBe 0.0
    }

    @Test
    fun `INV-AC-606 an outage shows the backlog in the metrics and a replay after it is accepted`(
        @TempDir dir: Path,
    ) = Harness.run(dir, "transport.ap.sandbox-endpoint=${ApSimulator.baseUrl}") { h ->
        val tenant = UUID.randomUUID()
        val queued = List(3) { DocRef(UUID.randomUUID(), 1) }
        h.assign(tenant, "E-001", h.primary)
        val registry = h.context.getBean(MeterRegistry::class.java)
        ApSimulator.control("/faults/outage", """{"enabled":true}""")
        queued.forEach { h.submitPayload(tenant, "E-001", it) shouldBe TransportOutcome.TransientFailure }
        // the backlog is visible while the outage lasts
        registry.value("transport.ap.failed.attempts.window", h.primary.value) shouldBe 3.0
        registry.value("transport.ap.failure.rate", h.primary.value) shouldBe 1.0
        ApSimulator.control("/faults/outage", """{"enabled":false}""")
        // manual replay: the same Doc UUIDs and versions are submitted again and are accepted
        queued.forEach { h.submitPayload(tenant, "E-001", it).shouldBeAccepted() }
        ApSimulator.submissions() shouldHaveSize 3
    }

    @Test
    fun `INV-AC-7856 an outage across a due date moves no due date because transport holds none`(
        @TempDir dir: Path,
    ) = Harness.run(dir, "transport.ap.sandbox-endpoint=${ApSimulator.baseUrl}") { h ->
        val tenant = UUID.randomUUID()
        h.assign(tenant, "E-001", h.primary)
        val before = h.schemaColumns()
        ApSimulator.control("/faults/outage", """{"enabled":true}""")
        val doc = DocRef(UUID.randomUUID(), 1)
        h.submitPayload(tenant, "E-001", doc) shouldBe TransportOutcome.TransientFailure
        ApSimulator.control("/faults/outage", """{"enabled":false}""")
        h.submitPayload(tenant, "E-001", doc).shouldBeAccepted()
        // no table of the schema has a due-date or deadline column that an outage could move
        h.schemaColumns().filter { it.contains("due") || it.contains("deadline") } shouldBe emptyList()
        h.schemaColumns() shouldBe before
        h.context
            .getBean(
                MeterRegistry::class.java,
            ).value("transport.ap.failed.attempts.window", h.primary.value) shouldBe
            1.0
    }

    private fun TransportOutcome.shouldBeAccepted() {
        (this is TransportOutcome.Accepted) shouldBe true
    }
}
