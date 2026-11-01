package com.dynokore.gst.aggregation.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PeriodSplitTest {
    private fun d(
        m: Int,
        day: Int,
    ) = LocalDate.of(2027, m, day)

    private val monthly =
        listOf(
            PeriodSpan(d(3, 1), d(3, 31), "2027-03"),
            PeriodSpan(d(4, 1), d(4, 30), "2027-04"),
            PeriodSpan(d(5, 1), d(5, 31), "2027-05"),
        )

    private fun parts(
        window: AggregationWindow,
        spans: List<PeriodSpan> = monthly,
    ) = (PeriodSplit.split(window, spans) as SplitResult.Split).parts

    private fun week(date: LocalDate) = AggregationWindow.containing(Cadence.WEEKLY, date)

    @Test
    fun `a window inside one span is one part`() {
        parts(week(d(4, 12))) shouldBe listOf(WindowPart(d(4, 12), d(4, 18), "2027-04"))
    }

    @Test
    fun `the week from 26 April to 2 May is split at 30 April`() {
        parts(week(d(4, 28))) shouldBe
            listOf(WindowPart(d(4, 26), d(4, 30), "2027-04"), WindowPart(d(5, 1), d(5, 2), "2027-05"))
    }

    @Test
    fun `the week from 29 March to 4 April is split at 31 March`() {
        parts(week(d(4, 1))) shouldBe
            listOf(WindowPart(d(3, 29), d(3, 31), "2027-03"), WindowPart(d(4, 1), d(4, 4), "2027-04"))
    }

    @Test
    fun `the span end is inclusive so a window ending on it is not split`() {
        parts(AggregationWindow(d(3, 25), d(3, 31))) shouldBe listOf(WindowPart(d(3, 25), d(3, 31), "2027-03"))
    }

    @Test
    fun `a daily window on the last day of a span is covered by that span`() {
        parts(AggregationWindow(d(3, 31), d(3, 31))) shouldBe listOf(WindowPart(d(3, 31), d(3, 31), "2027-03"))
        parts(AggregationWindow(d(4, 30), d(4, 30))) shouldBe listOf(WindowPart(d(4, 30), d(4, 30), "2027-04"))
    }

    @Test
    fun `the parts come in date order whatever the order of the spans`() {
        val result = parts(AggregationWindow(d(3, 30), d(5, 2)), monthly.reversed())
        result.map { it.start } shouldBe listOf(d(3, 30), d(4, 1), d(5, 1))
        result.map { it.periodRef } shouldBe listOf("2027-03", "2027-04", "2027-05")
    }

    @Test
    fun `a date no span covers makes the result Uncovered and returns no part`() {
        val gap = listOf(PeriodSpan(d(3, 1), d(3, 31), "2027-03"), PeriodSpan(d(4, 5), d(4, 30), "2027-04"))
        PeriodSplit.split(AggregationWindow(d(3, 29), d(4, 4)), gap) shouldBe SplitResult.Uncovered(d(4, 1))
        PeriodSplit.split(AggregationWindow(d(3, 29), d(4, 4)), emptyList()) shouldBe SplitResult.Uncovered(d(3, 29))
    }

    @Test
    fun `INV-AC-1573 the default entity has one PCP window for April`() {
        val window = AggregationWindow.containing(Cadence.defaultFor(AggregateType.PCP), d(4, 14))
        window shouldBe AggregationWindow(d(4, 1), d(4, 30))
        parts(window) shouldBe listOf(WindowPart(d(4, 1), d(4, 30), "2027-04"))
    }

    @Test
    fun `INV-AC-1573 the weekly entity has one window per week and each is split at 30 April if it crosses it`() {
        val days = generateSequence(d(4, 1)) { it.plusDays(1) }.takeWhile { it <= d(4, 30) }
        val weeks = days.map(::week).distinct().toList()
        weeks.map { it.start } shouldBe listOf(d(3, 29), d(4, 5), d(4, 12), d(4, 19), d(4, 26))
        weeks.map { parts(it).size } shouldBe listOf(2, 1, 1, 1, 2)
        weeks.flatMap { parts(it) }.forEach { part ->
            monthly.single { it.periodRef == part.periodRef }.let {
                (part.start >= it.start && part.end <= it.end) shouldBe true
            }
        }
    }
}
