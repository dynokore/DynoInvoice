package com.dynokore.gst.submission.application.completeness

import com.dynokore.gst.submission.domain.readiness.CompletenessState
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The AggregateWindowChanged consumer over in-memory stores (INV-AC-22204). */
class AggregateWindowChangedHandlerTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000021"))
    private val other = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000022"))
    private val from = LocalDate.of(2026, 4, 1)
    private val to = LocalDate.of(2026, 6, 30)
    private val memory = InMemoryCompleteness()
    private var next: () -> AggregateWindowChangedData = { error("unset") }

    private fun deliver(
        state: WindowChangeState,
        version: Long,
        t: TenantId = tenant,
    ): HandleOutcome {
        next = { AggregateWindowChangedData("E", "P", from, to, state, version) }
        return AggregateWindowChangedHandler(fixedTenants(t), { next() }, memory).handle(testEnvelope(t))
    }

    private fun derived() = StoredCompleteness.derive(memory.rows(tenant, "E", "P"))

    @Test
    fun `INV-AC-22204 LATE v1 APPLIED, CLOSED v2 APPLIED, versionless OPEN on the existing row IGNORED`() {
        deliver(WindowChangeState.LATE, 1) shouldBe HandleOutcome.APPLIED
        derived() shouldBe CompletenessState.Failed(listOf("window:P"))
        deliver(WindowChangeState.CLOSED, 2) shouldBe HandleOutcome.APPLIED
        derived() shouldBe CompletenessState.Passed
        deliver(WindowChangeState.OPEN, 0) shouldBe HandleOutcome.IGNORED
        derived() shouldBe CompletenessState.Passed
        memory.windowRows.values
            .single()
            .version shouldBe 2
    }

    @Test
    fun `INV-AC-22204 a versionless OPEN with no row is stored open at version 0 and derives Unavailable`() {
        deliver(WindowChangeState.OPEN, 0) shouldBe HandleOutcome.APPLIED
        memory.windowRows.values
            .single()
            .version shouldBe 0
        derived() shouldBe CompletenessState.Unavailable
        deliver(WindowChangeState.CLOSED, 1) shouldBe HandleOutcome.APPLIED
        derived() shouldBe CompletenessState.Passed
    }

    @Test
    fun `INV-AC-22204 an event of another tenant does not change a row of this tenant`() {
        deliver(WindowChangeState.LATE, 1)
        deliver(WindowChangeState.CLOSED, 9, other) shouldBe HandleOutcome.APPLIED
        derived() shouldBe CompletenessState.Failed(listOf("window:P"))
    }

    @Test
    fun `INV-AC-22204 feed and window rows of one entity and period derive together, sorted`() {
        memory.record(tenant, "F1", "E", "P", "VALUE", true, 0)
        memory.record(tenant, "F1", "E", "P", "COUNT", true, 2)
        deliver(WindowChangeState.LATE, 1)
        derived() shouldBe CompletenessState.Failed(listOf("feed:F1/COUNT", "feed:F1/VALUE", "window:P"))
    }

    @Test
    fun `INV-AC-22204 a malformed event is a ContractViolation that writes nothing and carries no value`() {
        val failing = AggregateWindowChangedDataReader { throw ContractViolation("window from is missing") }
        val h = AggregateWindowChangedHandler(fixedTenants(tenant), failing, memory)
        val e = shouldThrow<ContractViolation> { h.handle(testEnvelope(tenant)) }
        e.message.orEmpty() shouldNotContain "2026"
        memory.writes shouldBe 0
    }
}
