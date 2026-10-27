package com.dynokore.gst.canonical.domain.dating

import com.dynokore.gst.canonical.domain.dating.PeriodDecision.Assigned
import com.dynokore.gst.canonical.domain.dating.PeriodDecision.Held
import com.dynokore.gst.canonical.domain.dating.PeriodDecision.Inconsistent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Step 3 of the derivation order (INV-REQ-68 b1). */
class PeriodAssignmentTest {
    private val q1 = PeriodSpan("2026-Q1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))
    private val q2 = PeriodSpan("2026-Q2", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30))
    private val q3 = PeriodSpan("2026-Q3", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30))
    private val overlap = PeriodSpan("2026-X", LocalDate.of(2026, 3, 15), LocalDate.of(2026, 4, 15))

    private fun assign(
        date: String,
        vararg spans: PeriodSpan,
    ) = PeriodAssignment.assign(LocalDate.parse(date), spans.toList())

    @Test
    fun `INV-AC-108 the posting date of 2 April falls in the second quarter`() {
        assign("2026-04-02", q1, q2) shouldBe Assigned("2026-Q2")
    }

    @Test
    fun `INV-AC-138 the invoice date of 28 March falls in the first quarter`() {
        assign("2026-03-28", q1, q2) shouldBe Assigned("2026-Q1")
    }

    @Test
    fun `INV-AC-10590 both end dates are inclusive`() {
        assign("2026-01-01", q1, q2) shouldBe Assigned("2026-Q1")
        assign("2026-03-31", q1, q2) shouldBe Assigned("2026-Q1")
        assign("2026-04-01", q1, q2) shouldBe Assigned("2026-Q2")
    }

    @Test
    fun `INV-AC-10590 a date in no span is held with PER-009`() {
        assign("2026-04-15", q1, q3) shouldBe Held(HoldCode.PER_009)
        assign("2026-07-01", q1, q2) shouldBe Held(HoldCode.PER_009)
    }

    @Test
    fun `INV-AC-10590 an empty span list is held with PER-009`() {
        assign("2026-03-31") shouldBe Held(HoldCode.PER_009)
    }

    @Test
    fun `INV-AC-10590 two spans containing the date are inconsistent and nothing is picked`() {
        val result = assign("2026-03-20", q1, overlap)
        result shouldBe Inconsistent(listOf("2026-Q1", "2026-X"))
        (result as Inconsistent).failureCode shouldBe "SRT-010"
        assign("2026-03-10", q1, overlap) shouldBe Assigned("2026-Q1")
    }

    @Test
    fun `INV-AC-10590 the period reference comes back exactly as given`() {
        val odd = PeriodSpan("2026Q1", q1.start, q1.end)
        assign("2026-02-01", odd) shouldBe Assigned("2026Q1")
        assign("2026-02-01", q1) shouldBe Assigned("2026-Q1")
    }

    @Test
    fun `INV-AC-10590 a span ending before it starts and a blank reference are refused`() {
        shouldThrow<IllegalArgumentException> { PeriodSpan("P", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 3, 31)) }
        shouldThrow<IllegalArgumentException> { PeriodSpan("", q1.start, q1.end) }
        shouldThrow<IllegalArgumentException> { PeriodSpan("  ", q1.start, q1.end) }
    }
}
