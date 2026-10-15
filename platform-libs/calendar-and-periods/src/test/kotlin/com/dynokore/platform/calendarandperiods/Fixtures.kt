package com.dynokore.platform.calendarandperiods

import java.time.DayOfWeek
import java.time.LocalDate

/** The fixture calendar pack of the tests: windows, due rule, weekend and holidays are data, as a real pack's are. */
internal object Fixtures {
    val quarterly = FilingFrequency("QUARTERLY")
    val monthly = FilingFrequency("MONTHLY")

    fun d(text: String): LocalDate = LocalDate.parse(text)

    private fun quarterWindows(year: Int) =
        listOf(
            StandardWindow(d("$year-01-01"), d("$year-03-31"), "F5"),
            StandardWindow(d("$year-04-01"), d("$year-06-30"), "F5"),
            StandardWindow(d("$year-07-01"), d("$year-09-30"), "F5"),
            StandardWindow(d("$year-10-01"), d("$year-12-31"), "F5"),
        )

    private fun monthWindows(year: Int) =
        (1..12).map {
            val month = java.time.YearMonth.of(year, it)
            StandardWindow(month.atDay(1), month.atEndOfMonth(), "F5")
        }

    fun pack(
        holidays: Set<LocalDate> = emptySet(),
        pinnedRule: NonBusinessDayRule? = null,
        weekend: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
    ) = CalendarPack(
        version = "test-1",
        windows =
            mapOf(
                quarterly to (quarterWindows(2026) + quarterWindows(2027)),
                monthly to (monthWindows(2026) + monthWindows(2027)),
            ),
        finalReturnForm = "F8",
        dueRules =
            mapOf(
                "F5" to DueRule(monthsAfterEnd = 1, endOfMonth = true, daysAfterEnd = 0),
                "F8" to DueRule(monthsAfterEnd = 1, endOfMonth = true, daysAfterEnd = 0),
            ),
        calendar = BusinessCalendar(weekend, holidays),
        pinnedRule = pinnedRule,
    )

    fun history(vararg changes: Pair<String, FilingFrequency>) =
        FrequencyHistory.of(changes.map { (from, f) -> FrequencyChange(d(from), f) })
}
