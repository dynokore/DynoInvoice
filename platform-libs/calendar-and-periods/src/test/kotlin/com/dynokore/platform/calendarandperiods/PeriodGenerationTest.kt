package com.dynokore.platform.calendarandperiods

import com.dynokore.platform.calendarandperiods.Fixtures.d
import com.dynokore.platform.calendarandperiods.Fixtures.history
import com.dynokore.platform.calendarandperiods.Fixtures.monthly
import com.dynokore.platform.calendarandperiods.Fixtures.pack
import com.dynokore.platform.calendarandperiods.Fixtures.quarterly
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** INV-REQ-83, INV-REQ-84, INV-REQ-688, INV-REQ-707: generated records against a fixture table. */
class PeriodGenerationTest {
    private fun generate(
        registration: String,
        history: FrequencyHistory,
        through: String,
        deregistration: String? = null,
        pack: CalendarPack = pack(),
        rule: NonBusinessDayRule = NonBusinessDayRule.NO_MOVE,
    ) = PackPeriodGenerator(pack).generate(
        PeriodRequest(d(registration), deregistration?.let { d(it) }, history, d(through), rule),
    )

    /** Rows: start,end,returnForm,dueDate,kinds (kinds joined by `+`, `-` for a standard period). */
    private fun table(resource: String): List<String> =
        javaClass
            .getResourceAsStream("/fixtures/$resource")!!
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }

    private fun PeriodRecord.row() =
        listOf(start, end, returnForm, dueDate, kinds.sorted().joinToString("+").ifEmpty { "-" }).joinToString(",")

    @Test
    fun `a registration on 15 Feb 2026 on a quarterly cycle gives an irregular first period`() {
        val periods = generate("2026-02-15", history("2026-02-15" to quarterly), "2026-12-31")

        assertEquals(table("registration-quarterly.csv"), periods.map { it.row() })
        assertTrue(periods.first().irregular)
        assertEquals(d("2026-02-15"), periods.first().start)
        assertEquals(d("2026-03-31"), periods.first().end)
        assertFalse(periods[1].irregular)
        assertTrue(periods.none { it.confirmed }, "unconfirmed until confirmPeriods")
    }

    @Test
    fun `a registration on a window start gives a standard first period`() {
        val periods = generate("2026-01-01", history("2026-01-01" to quarterly), "2026-03-31")

        assertEquals(1, periods.size)
        assertFalse(periods.single().irregular)
    }

    @Test
    fun `membership is a date-range test against the record`() {
        val first = generate("2026-02-15", history("2026-02-15" to quarterly), "2026-03-31").single()

        assertTrue(d("2026-02-15") in first)
        assertTrue(d("2026-03-31") in first)
        assertFalse(d("2026-02-14") in first)
        assertFalse(d("2026-04-01") in first)
    }

    @Test
    fun `a deregistration gives a final period on the final return form`() {
        val periods =
            generate("2026-01-01", history("2026-01-01" to quarterly), "2026-12-31", deregistration = "2026-05-20")

        assertEquals(table("deregistration-quarterly.csv"), periods.map { it.row() })
        assertTrue(periods.last().irregular)
        assertEquals(setOf(PeriodKind.FINAL), periods.last().kinds)
    }

    @Test
    fun `a registration and a deregistration in one window give a first and final period`() {
        val periods =
            generate("2026-02-15", history("2026-02-15" to quarterly), "2026-12-31", deregistration = "2026-03-10")

        assertEquals(setOf(PeriodKind.FIRST, PeriodKind.FINAL), periods.single().kinds)
        assertEquals("F8", periods.single().returnForm)
    }

    @Test
    fun `a frequency change cut at the change gives a transitional period of stated length`() {
        val periods =
            generate(
                "2026-01-01",
                history("2026-01-01" to quarterly, "2026-05-10" to monthly),
                "2026-07-31",
            )

        assertEquals(table("change-cut.csv"), periods.map { it.row() })
        val transitional = periods[1]
        assertEquals(d("2026-04-01"), transitional.start)
        assertEquals(d("2026-05-09"), transitional.end)
        assertEquals(39, transitional.lengthInDays)
    }

    @Test
    fun `a frequency change extended to the new window gives a long transitional period`() {
        val periods =
            generate(
                "2026-01-01",
                history("2026-01-01" to quarterly, "2026-05-10" to monthly),
                "2026-07-31",
                pack = pack(transition = TransitionStyle.EXTEND_TO_NEW_WINDOW),
            )

        assertEquals(table("change-extend.csv"), periods.map { it.row() })
        assertEquals(61, periods[1].lengthInDays)
    }

    @Test
    fun `a change on a window boundary gives no transitional period`() {
        val periods =
            generate("2026-01-01", history("2026-01-01" to quarterly, "2026-04-01" to monthly), "2026-05-31")

        assertTrue(periods.none { it.irregular })
        assertEquals(listOf(d("2026-03-31"), d("2026-04-30"), d("2026-05-31")), periods.map { it.end })
    }

    @Test
    fun `a pack with no window for the date fails with a coverage error`() {
        assertThrows(CalendarPackCoverageException::class.java) {
            generate("2026-02-15", history("2026-02-15" to quarterly), "2028-12-31")
        }
    }

    @Test
    fun `a history that does not cover the registration date is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            generate("2026-01-01", history("2026-02-01" to quarterly), "2026-12-31")
        }
    }
}
