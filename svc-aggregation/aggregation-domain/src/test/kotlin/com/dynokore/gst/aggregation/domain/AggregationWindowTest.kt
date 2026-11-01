package com.dynokore.gst.aggregation.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate

class AggregationWindowTest {
    private fun d(
        y: Int,
        m: Int,
        day: Int,
    ) = LocalDate.of(y, m, day)

    @Test
    fun `the default cadence is daily for POS and STI and monthly for PCP`() {
        Cadence.defaultFor(AggregateType.POS) shouldBe Cadence.DAILY
        Cadence.defaultFor(AggregateType.STI) shouldBe Cadence.DAILY
        Cadence.defaultFor(AggregateType.PCP) shouldBe Cadence.MONTHLY
    }

    @Test
    fun `the allowed cadences are daily or weekly for POS and STI and monthly or weekly for PCP`() {
        Cadence.allowedFor(AggregateType.POS) shouldBe setOf(Cadence.DAILY, Cadence.WEEKLY)
        Cadence.allowedFor(AggregateType.STI) shouldBe setOf(Cadence.DAILY, Cadence.WEEKLY)
        Cadence.allowedFor(AggregateType.PCP) shouldBe setOf(Cadence.MONTHLY, Cadence.WEEKLY)
    }

    @Test
    fun `every default cadence is an allowed one`() {
        AggregateType.entries.forEach { Cadence.allowedFor(it).contains(Cadence.defaultFor(it)) shouldBe true }
    }

    @Test
    fun `the window states carry the names of the contract enum`() {
        WindowState.entries.map { it.name } shouldBe listOf("OPEN", "CLOSED", "LATE")
    }

    @Test
    fun `a daily window is the single day`() {
        AggregationWindow.containing(Cadence.DAILY, d(2027, 3, 31)) shouldBe
            AggregationWindow(d(2027, 3, 31), d(2027, 3, 31))
    }

    @Test
    fun `a weekly window runs from Monday to Sunday for every day of the week`() {
        val monday = d(2027, 4, 26)
        (0..6L).forEach { offset ->
            val window = AggregationWindow.containing(Cadence.WEEKLY, monday.plusDays(offset))
            window shouldBe AggregationWindow(monday, d(2027, 5, 2))
            window.start.dayOfWeek shouldBe DayOfWeek.MONDAY
            window.end.dayOfWeek shouldBe DayOfWeek.SUNDAY
        }
    }

    @Test
    fun `a Sunday belongs to the week that ends on it and a Monday starts the next`() {
        AggregationWindow.containing(Cadence.WEEKLY, d(2027, 4, 4)) shouldBe
            AggregationWindow(d(2027, 3, 29), d(2027, 4, 4))
        AggregationWindow.containing(Cadence.WEEKLY, d(2027, 4, 5)) shouldBe
            AggregationWindow(d(2027, 4, 5), d(2027, 4, 11))
    }

    @Test
    fun `a monthly window is the calendar month with its real length`() {
        AggregationWindow.containing(Cadence.MONTHLY, d(2027, 4, 15)) shouldBe
            AggregationWindow(d(2027, 4, 1), d(2027, 4, 30))
        AggregationWindow.containing(Cadence.MONTHLY, d(2027, 5, 31)) shouldBe
            AggregationWindow(d(2027, 5, 1), d(2027, 5, 31))
        AggregationWindow.containing(Cadence.MONTHLY, d(2028, 2, 10)) shouldBe
            AggregationWindow(d(2028, 2, 1), d(2028, 2, 29))
    }
}
