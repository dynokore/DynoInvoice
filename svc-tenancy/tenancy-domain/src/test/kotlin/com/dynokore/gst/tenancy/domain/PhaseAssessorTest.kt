package com.dynokore.gst.tenancy.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class PhaseAssessorTest {
    private fun ret(
        start: String,
        end: String,
        box4: String,
    ) = GstReturn(LocalDate.parse(start), LocalDate.parse(end), BigDecimal(box4))

    private fun assessed(outcome: PhaseOutcome) = (outcome as PhaseOutcome.Assessed).assessment

    private val quarterlyJanApr =
        listOf(
            ret("2024-11-01", "2025-01-31", "10000"),
            ret("2025-02-01", "2025-04-30", "20000"),
            ret("2025-05-01", "2025-07-31", "30000"),
            ret("2025-08-01", "2025-10-31", "40000"),
        )

    @Test
    fun `periods ending Jan Apr Jul and Oct sum the window Nov 2024 to Oct 2025 and record the returns used`() {
        val a = assessed(PhaseAssessor.assess(quarterlyJanApr + ret("2024-08-01", "2024-10-31", "999999"), false))
        assertEquals(PhaseWindow.NOV_TO_OCT, a.inputs.window)
        assertEquals(quarterlyJanApr, a.inputs.returnsUsed)
        assertEquals(BigDecimal("100000"), a.totalAnnualSupplies)
        assertEquals(LocalDate.of(2028, 4, 1), a.mandatoryDate)
    }

    @Test
    fun `the other two window rows select their period-end months`() {
        assertEquals(PhaseWindow.JAN_TO_DEC, PhaseWindow.ofPeriodEndMonth(java.time.Month.SEPTEMBER))
        assertEquals(PhaseWindow.DEC_TO_NOV, PhaseWindow.ofPeriodEndMonth(java.time.Month.AUGUST))
    }

    @Test
    fun `returns covering 200 days are extrapolated by 365 over 200 only when chosen`() {
        val returns = listOf(ret("2025-03-15", "2025-06-30", "60000"), ret("2025-07-01", "2025-09-30", "40000"))
        assertEquals(200, returns.sumOf { it.days })
        val extrapolated = assessed(PhaseAssessor.assess(returns, true))
        assertEquals(0, BigDecimal("182500").compareTo(extrapolated.totalAnnualSupplies))
        assertEquals(200, extrapolated.inputs.daysCovered)
        assertTrue(extrapolated.inputs.extrapolationChosen)
        assertEquals(BigDecimal("100000"), extrapolated.inputs.summedBox4)
        val plain = assessed(PhaseAssessor.assess(returns, false))
        assertEquals(BigDecimal("100000"), plain.totalAnnualSupplies)
    }

    @Test
    fun `a total exactly at a threshold falls into the earlier wave`() {
        fun date(total: String) =
            assessed(PhaseAssessor.assess(listOf(ret("2025-01-01", "2025-12-31", total)), false)).mandatoryDate
        assertEquals(LocalDate.of(2028, 4, 1), date("200000"))
        assertEquals(LocalDate.of(2029, 4, 1), date("200000.01"))
        assertEquals(LocalDate.of(2029, 4, 1), date("1000000"))
        assertEquals(LocalDate.of(2030, 4, 1), date("1000000.01"))
        assertEquals(LocalDate.of(2030, 4, 1), date("4000000"))
        assertEquals(LocalDate.of(2031, 4, 1), date("4000000.01"))
    }

    @Test
    fun `four nil returns total zero and fall into 1 Apr 2028`() {
        val nil =
            listOf(
                ret("2025-01-01", "2025-03-31", "0"),
                ret("2025-04-01", "2025-06-30", "0"),
                ret("2025-07-01", "2025-09-30", "0"),
                ret("2025-10-01", "2025-12-31", "0"),
            )
        val a = assessed(PhaseAssessor.assess(nil, false))
        assertEquals(0, BigDecimal.ZERO.compareTo(a.totalAnnualSupplies))
        assertEquals(LocalDate.of(2028, 4, 1), a.mandatoryDate)
    }

    @Test
    fun `no return ending in 2025 leaves the date outstanding and mixed period-end months are not assessed`() {
        assertEquals(PhaseOutcome.Outstanding, PhaseAssessor.assess(emptyList(), false))
        assertEquals(
            PhaseOutcome.Outstanding,
            PhaseAssessor.assess(listOf(ret("2024-01-01", "2024-12-31", "5")), false),
        )
        val mixed = listOf(ret("2025-01-01", "2025-03-31", "1"), ret("2025-04-01", "2025-04-30", "1"))
        assertEquals(PhaseOutcome.MixedPeriodEndMonths, PhaseAssessor.assess(mixed, false))
    }

    @Test
    fun `status in force on a date is the latest entry on or before it`() {
        data class E(
            override val effectiveFrom: LocalDate,
            val v: String,
        ) : Effective
        val history = listOf(E(LocalDate.of(2026, 1, 1), "a"), E(LocalDate.of(2026, 7, 1), "b"))
        assertEquals("a", StatusTimeline.inForceOn(history, LocalDate.of(2026, 6, 30))?.v)
        assertEquals("b", StatusTimeline.inForceOn(history, LocalDate.of(2026, 7, 1))?.v)
        assertEquals(null, StatusTimeline.inForceOn(history, LocalDate.of(2025, 12, 31)))
    }
}
