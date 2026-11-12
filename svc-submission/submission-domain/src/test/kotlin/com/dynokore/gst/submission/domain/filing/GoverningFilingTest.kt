package com.dynokore.gst.submission.domain.filing

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class GoverningFilingTest {
    private fun d(day: Int) = LocalDate.of(2026, 4, day)

    private fun f(
        day: Int,
        correction: Boolean = false,
    ) = FiledFact(d(day), correction)

    @Test
    fun `INV-AC-20622 no fact gives no governing date`() {
        GoverningFiling.of(emptyList()) shouldBe null
    }

    @Test
    fun `INV-AC-20622 the first fact governs and there is no conflict`() {
        GoverningFiling.of(listOf(f(18))) shouldBe Governing(d(18), null)
    }

    @Test
    fun `INV-AC-20622 the same date twice is no conflict`() {
        GoverningFiling.of(listOf(f(18), f(18))) shouldBe Governing(d(18), null)
    }

    @Test
    fun `INV-AC-20622 two dates give the earlier one and a PER-010 conflict with the other date`() {
        GoverningFiling.of(listOf(f(18), f(20))) shouldBe Governing(d(18), FilingConflict(d(20)))
        GoverningFiling.of(listOf(f(20), f(18))) shouldBe Governing(d(18), FilingConflict(d(20)))
        GoverningFiling.of(listOf(f(18), f(20)))!!.conflict!!.code shouldBe "PER-010"
    }

    @Test
    fun `INV-AC-20622 the other date is the earliest differing later date`() {
        GoverningFiling.of(listOf(f(18), f(25), f(21), f(18))) shouldBe Governing(d(18), FilingConflict(d(21)))
    }

    @Test
    fun `INV-AC-20622 the latest correction governs and leaves no conflict`() {
        GoverningFiling.of(listOf(f(18), f(20), f(19, true))) shouldBe Governing(d(19), null)
        GoverningFiling.of(listOf(f(18), f(17, true), f(25, true), f(20))) shouldBe Governing(d(25), null)
    }

    @Test
    fun `a conflict refuses a code other than PER-010`() {
        runCatching { FilingConflict(d(20), "PER-001") }.isFailure shouldBe true
    }
}
