package com.dynokore.gst.evidence.domain.cases

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class BreachDeadlinesTest {
    private val suspicion = Instant.parse("2026-03-01T08:00:00Z")
    private val terms = BreachTerms(Duration.ofHours(72), Duration.ofHours(24))

    @Test
    fun `INV-AC-13387 assessment is due at the suspicion plus the term and no notification yet`() {
        BreachClock.compute(terms, suspicion, null) shouldBe
            BreachDeadlines.Computed(
                BreachDeadline(BreachDeadlineKind.ASSESSMENT, Instant.parse("2026-03-04T08:00:00Z")),
                null,
            )
    }

    @Test
    fun `INV-AC-13387 notification is due at the assessed instant plus the term`() {
        val assessed = Instant.parse("2026-03-02T08:00:00Z")
        BreachClock.compute(terms, suspicion, assessed) shouldBe
            BreachDeadlines.Computed(
                BreachDeadline(BreachDeadlineKind.ASSESSMENT, Instant.parse("2026-03-04T08:00:00Z")),
                BreachDeadline(BreachDeadlineKind.NOTIFICATION, Instant.parse("2026-03-03T08:00:00Z")),
            )
    }

    @Test
    fun `INV-AC-13387 no terms is not configured for both inputs`() {
        BreachClock.compute(null, suspicion, null) shouldBe BreachDeadlines.NotConfigured
        val assessed = Instant.parse("2026-03-02T08:00:00Z")
        BreachClock.compute(null, suspicion, assessed) shouldBe BreachDeadlines.NotConfigured
    }

    @Test
    fun `a deadline is not overdue at its due instant and is overdue one second later`() {
        val due = BreachDeadline(BreachDeadlineKind.ASSESSMENT, Instant.parse("2026-03-04T08:00:00Z"))
        BreachClock.isOverdue(due, due.dueAt) shouldBe false
        BreachClock.isOverdue(due, due.dueAt.minusSeconds(1)) shouldBe false
        BreachClock.isOverdue(due, due.dueAt.plusSeconds(1)) shouldBe true
    }

    @Test
    fun `an assessment one second before the suspicion throws`() {
        shouldThrow<IllegalArgumentException> { BreachClock.compute(terms, suspicion, suspicion.minusSeconds(1)) }
    }

    @Test
    fun `an assessment at the suspicion instant is accepted`() {
        BreachClock.compute(terms, suspicion, suspicion) shouldBe
            BreachDeadlines.Computed(
                BreachDeadline(BreachDeadlineKind.ASSESSMENT, suspicion.plus(Duration.ofHours(72))),
                BreachDeadline(BreachDeadlineKind.NOTIFICATION, suspicion.plus(Duration.ofHours(24))),
            )
    }

    @Test
    fun `a zero or negative term throws`() {
        shouldThrow<IllegalArgumentException> { BreachTerms(Duration.ZERO, Duration.ofHours(1)) }
        shouldThrow<IllegalArgumentException> { BreachTerms(Duration.ofHours(1), Duration.ZERO) }
        shouldThrow<IllegalArgumentException> { BreachTerms(Duration.ofHours(-1), Duration.ofHours(1)) }
        shouldThrow<IllegalArgumentException> { BreachTerms(Duration.ofHours(1), Duration.ofHours(-1)) }
    }

    @Test
    fun `the same instants give the same deadlines across a daylight saving change`() {
        val start = ZonedDateTime.of(2026, 3, 7, 12, 0, 0, 0, ZoneId.of("America/New_York")).toInstant()
        val result = BreachClock.compute(terms, start, null) as BreachDeadlines.Computed
        result.assessment.dueAt shouldBe start.plus(Duration.ofHours(72))
        val other = ZonedDateTime.ofInstant(start, ZoneId.of("Asia/Singapore")).toInstant()
        BreachClock.compute(terms, other, null) shouldBe result
    }

    @Test
    fun `terms of one hour each are applied without a default or floor`() {
        val one = BreachTerms(Duration.ofHours(1), Duration.ofHours(1))
        val assessed = suspicion.plusSeconds(600)
        BreachClock.compute(one, suspicion, assessed) shouldBe
            BreachDeadlines.Computed(
                BreachDeadline(BreachDeadlineKind.ASSESSMENT, suspicion.plus(Duration.ofHours(1))),
                BreachDeadline(BreachDeadlineKind.NOTIFICATION, assessed.plus(Duration.ofHours(1))),
            )
    }
}
