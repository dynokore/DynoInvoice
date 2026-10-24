package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.application.period.CalendarPackSource
import com.dynokore.platform.calendarandperiods.BusinessCalendar
import com.dynokore.platform.calendarandperiods.CalendarPack
import com.dynokore.platform.calendarandperiods.DueRule
import com.dynokore.platform.calendarandperiods.FilingFrequency
import com.dynokore.platform.calendarandperiods.StandardWindow
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** The pack the test profile serves: built with the library constructor from test data, not the Singapore rule. */
object FixturePack {
    val QUARTERLY = FilingFrequency("QUARTERLY")
    val MONTHLY = FilingFrequency("MONTHLY")

    private fun quarters(year: Int) =
        (0 until 4).map {
            val start = LocalDate.of(year, it * 3 + 1, 1)
            StandardWindow(start, YearMonth.of(year, it * 3 + 3).atEndOfMonth(), "F5")
        }

    private fun months(year: Int) =
        (1..12).map { StandardWindow(LocalDate.of(year, it, 1), YearMonth.of(year, it).atEndOfMonth(), "F5") }

    fun pack(frequencies: Set<FilingFrequency> = setOf(QUARTERLY, MONTHLY)) =
        CalendarPack(
            "fixture-1",
            buildMap {
                if (QUARTERLY in frequencies) put(QUARTERLY, quarters(2025) + quarters(2026) + quarters(2027))
                if (MONTHLY in frequencies) put(MONTHLY, months(2025) + months(2026) + months(2027))
            },
            "F8",
            mapOf("F5" to DueRule(1, true, 0), "F8" to DueRule(3, true, 0)),
            BusinessCalendar(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), emptySet()),
            null,
        )
}

/** The fixture adapter of the port `CalendarPackSource` (test profile only; the production profile has none). */
class FixtureCalendar : CalendarPackSource {
    @Volatile var pack: CalendarPack? = FixturePack.pack()

    override fun inForce(on: LocalDate): CalendarPack? = pack
}

@Configuration(proxyBeanMethods = false)
@Profile("test")
class FixtureAdapters {
    @Bean
    fun fixtureCalendar(): FixtureCalendar = FixtureCalendar()
}
