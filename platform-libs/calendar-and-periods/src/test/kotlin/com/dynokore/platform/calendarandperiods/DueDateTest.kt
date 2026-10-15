package com.dynokore.platform.calendarandperiods

import com.dynokore.platform.calendarandperiods.Fixtures.d
import com.dynokore.platform.calendarandperiods.Fixtures.pack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** INV-REQ-87: the non-business-day rule is a parameter, the holiday list is pack data. */
class DueDateTest {
    // A period ending 30 Apr 2026 is due 31 May 2026, a Sunday.
    private val sundayDue = d("2026-05-31")
    private val periodEnd = d("2026-04-30")

    private fun derive(
        pack: CalendarPack,
        rule: NonBusinessDayRule,
    ) = PackDueDates(pack).derive(periodEnd, "F5", rule)

    @Test
    fun `NO_MOVE leaves a Sunday due date unchanged`() {
        val due = derive(pack(), NonBusinessDayRule.NO_MOVE)

        assertEquals(sundayDue, due.adjusted)
        assertFalse(due.moved)
    }

    @Test
    fun `NEXT_WORKING_DAY moves a Sunday due date to Monday`() {
        val due = derive(pack(), NonBusinessDayRule.NEXT_WORKING_DAY)

        assertEquals(d("2026-06-01"), due.adjusted)
        assertEquals(sundayDue, due.unadjusted)
        assertTrue(due.moved)
    }

    @Test
    fun `probe - changing only the pack input moves the due date`() {
        val rule = NonBusinessDayRule.NEXT_WORKING_DAY
        val withoutHoliday = derive(pack(), rule).adjusted
        val withHoliday = derive(pack(holidays = setOf(d("2026-06-01"))), rule).adjusted

        assertEquals(d("2026-06-01"), withoutHoliday)
        assertEquals(d("2026-06-02"), withHoliday)
    }

    @Test
    fun `a holiday on a weekday moves the due date, and consecutive holidays chain`() {
        val holidays = setOf(d("2026-06-01"), d("2026-06-02"))

        assertEquals(d("2026-06-03"), derive(pack(holidays = holidays), NonBusinessDayRule.NEXT_WORKING_DAY).adjusted)
    }

    @Test
    fun `a pinned pack rule applies to every entity`() {
        val pinned = pack(pinnedRule = NonBusinessDayRule.NEXT_WORKING_DAY)

        assertEquals(d("2026-06-01"), derive(pinned, NonBusinessDayRule.NO_MOVE).adjusted)
        assertEquals(NonBusinessDayRule.NEXT_WORKING_DAY, derive(pinned, NonBusinessDayRule.NO_MOVE).rule)
    }

    @Test
    fun `the generated record carries the rule's effect`() {
        val periods =
            PackPeriodGenerator(pack()).generate(
                PeriodRequest(
                    d("2026-04-01"),
                    null,
                    Fixtures.history("2026-04-01" to Fixtures.monthly),
                    d("2026-04-30"),
                    NonBusinessDayRule.NEXT_WORKING_DAY,
                ),
            )

        assertEquals(d("2026-06-01"), periods.single().dueDate)
        assertEquals(sundayDue, periods.single().unadjustedDueDate)
    }

    @Test
    fun `a return form with no due rule fails with a coverage error`() {
        assertThrows(CalendarPackCoverageException::class.java) {
            PackDueDates(pack()).derive(periodEnd, "F9", NonBusinessDayRule.NO_MOVE)
        }
    }

    @Test
    fun `a calendar whose every day is a weekend is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            BusinessCalendar(
                java.time.DayOfWeek.entries
                    .toSet(),
                emptySet(),
            )
        }
    }

    @Test
    fun `two overlapping windows of one frequency are refused when the pack is built`() {
        val overlapping =
            mapOf(
                Fixtures.quarterly to
                    listOf(
                        StandardWindow(d("2026-01-01"), d("2026-03-31"), "F5"),
                        StandardWindow(d("2026-03-31"), d("2026-06-30"), "F5"),
                    ),
            )

        assertThrows(IllegalArgumentException::class.java) {
            CalendarPack(
                version = "overlap",
                windows = overlapping,
                finalReturnForm = "F8",
                dueRules = emptyMap(),
                calendar = BusinessCalendar(emptySet(), emptySet()),
                pinnedRule = null,
            )
        }
    }
}
