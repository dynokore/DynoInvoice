package com.dynokore.platform.calendarandperiods

import com.dynokore.platform.calendarandperiods.Fixtures.d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Consumer-driven contract test of the module API (the ModuleApiConsumerContractTest pattern): a stub written the way
 * `tenancy-svc` writes its period-calendar use case (INV-T-4929, INV-T-4930) — constructor-injected against only the
 * `-api` ports [PeriodGenerator] and [DueDates] and the readiness gate — run against the real implementations. If the
 * generated records, the due-date rule or the verdict drift from what the service is written to expect, this fails.
 * `tenancy-svc` does not exist yet: the stub plays its role.
 */
class ModuleApiConsumerContractTest {
    private class TenancySvcStub(
        private val generator: PeriodGenerator,
        private val dueDates: DueDates,
    ) {
        fun onboard(
            registered: LocalDate,
            history: FrequencyHistory,
            through: LocalDate,
            rule: NonBusinessDayRule,
        ): List<PeriodRecord> = generator.generate(PeriodRequest(registered, null, history, through, rule))

        fun periodHolding(
            periods: List<PeriodRecord>,
            date: LocalDate,
        ): PeriodRecord? = periods.firstOrNull { date in it }

        fun dueDateOf(
            period: PeriodRecord,
            rule: NonBusinessDayRule,
        ): LocalDate = dueDates.derive(period.end, period.returnForm, rule).adjusted
    }

    private fun stub(pack: CalendarPack) = TenancySvcStub(PackPeriodGenerator(pack), PackDueDates(pack))

    @Test
    fun `onboarding mid-quarter gives an unconfirmed irregular first period the service can assign documents to`() {
        val periods =
            stub(Fixtures.pack()).onboard(
                d("2026-02-15"),
                Fixtures.history("2026-02-15" to Fixtures.quarterly),
                d("2026-06-30"),
                NonBusinessDayRule.NO_MOVE,
            )

        val first = periods.first()
        assertEquals(d("2026-02-15"), first.start)
        assertEquals(d("2026-03-31"), first.end)
        assertTrue(first.irregular && !first.confirmed)
        assertEquals(first, stub(Fixtures.pack()).periodHolding(periods, d("2026-03-01")))
        assertEquals(null, stub(Fixtures.pack()).periodHolding(periods, d("2026-02-14")))
    }

    @Test
    fun `a calendar pack release that adds a holiday moves the due date the service reads back`() {
        val rule = NonBusinessDayRule.NEXT_WORKING_DAY
        val history = Fixtures.history("2026-04-01" to Fixtures.monthly)
        val before = stub(Fixtures.pack())
        val after = stub(Fixtures.pack(holidays = setOf(d("2026-06-01"))))

        val april = before.onboard(d("2026-04-01"), history, d("2026-04-30"), rule).single()

        assertEquals(d("2026-06-01"), before.dueDateOf(april, rule))
        assertEquals(d("2026-06-02"), after.dueDateOf(april, rule))
    }

    @Test
    fun `submission-svc style readiness gating blocks on an incomplete control`() {
        val verdict = gatePeriodReadiness(listOf(CompletenessControl("DOC-COMPLETE", false)))

        assertEquals(ReadinessVerdict.Blocked(listOf("DOC-COMPLETE")), verdict)
    }

    @Test
    fun `canonical-svc style period assignment gives the period of the boundary-zone date`() {
        val quarter = { start: String, end: String -> PeriodRecord(d(start), d(end), "F5", d(end), d(end), emptySet()) }
        val march = quarter("2026-01-01", "2026-03-31")
        val april = quarter("2026-04-01", "2026-06-30")
        val sg = java.time.ZoneId.of("Asia/Singapore")
        val ts = TransactionTimestamp.Explicit(java.time.ZonedDateTime.parse("2026-03-31T23:30+01:00[Europe/London]"))

        val result = assignPeriod(PeriodSet.of(listOf(march, april)), ts, sg, sg)

        assertEquals(PeriodAssignment.Assigned::class, result::class)
        assertEquals(april, (result as PeriodAssignment.Assigned).period)
        assertEquals(d("2026-04-01"), result.boundaryDate)
    }

    @Test
    fun `submission-svc style acknowledgement timers pull T2 forward to the hard stop`() {
        val zone = java.time.ZoneId.of("Asia/Singapore")
        val due = java.time.ZonedDateTime.of(2026, 4, 30, 23, 59, 59, 0, zone)
        val stop = hardStop(due, java.time.Duration.ofHours(48))
        val sent = stop.minus(java.time.Duration.ofHours(1))

        val s = escalationSchedule(sent, java.time.Duration.ofMinutes(30), java.time.Duration.ofHours(4), stop)

        assertEquals(stop, s.t2)
        assertTrue(s.t2PulledForward)
        assertEquals(sent.plus(java.time.Duration.ofMinutes(30)), s.t1)
    }

    @Test
    fun `evidence-svc style request case holds the deadline and prompts the notice as it nears`() {
        val deadline = responseDeadline(d("2026-03-01"), 30, null)

        assertEquals(d("2026-03-31"), deadline)
        assertEquals(false, noticeDue(deadline, d("2026-03-23"), 7))
        assertEquals(true, noticeDue(deadline, d("2026-03-24"), 7))
    }

    @Test
    fun `submission-svc style despatcher sends a weekly-window document at once when the margin falls first`() {
        val due = java.time.ZonedDateTime.of(2026, 4, 29, 0, 0, 0, 0, java.time.ZoneOffset.UTC)
        val ready = java.time.ZonedDateTime.of(2026, 4, 18, 9, 0, 0, 0, java.time.ZoneOffset.UTC)
        val stop = hardStop(due, java.time.Duration.ofDays(10))

        val sent = despatchAt(ready.toInstant(), nextWindowOpening(ready, BatchWindow.WEEKLY), stop)

        assertEquals(DespatchReason.IMMEDIATE_MARGIN, sent.reason)
        assertEquals(ready.toInstant(), sent.at)
    }

    @Test
    fun `ingestion-svc style setting check refuses weekly extraction plus weekly despatch beyond the gap`() {
        val gap = shortestGap(Fixtures.pack(), Fixtures.monthly)
        val margin = java.time.Duration.ofDays(15)

        assertEquals(false, weeklyWindowFits(BatchWindow.WEEKLY.interval, BatchWindow.WEEKLY.interval, gap, margin))
        assertEquals(true, weeklyWindowFits(BatchWindow.DAILY.interval, BatchWindow.CONTINUOUS.interval, gap, margin))
    }

    @Test
    fun `notification-svc style alert check reads the thresholds and the margin of the pack`() {
        val base = Fixtures.pack()
        val pack =
            CalendarPack(
                base.version,
                mapOf(Fixtures.monthly to base.windowsOf(Fixtures.monthly)),
                base.finalReturnForm,
                base.dueRules,
                base.calendar,
                null,
                mapOf(Fixtures.monthly to listOf(5, 2)),
                java.time.Duration.ofDays(3),
            )

        assertEquals(listOf(5, 2), pack.defaultAlertThresholds(Fixtures.monthly))
        assertEquals(java.time.Duration.ofDays(3), pack.defaultSafetyMargin())
    }
}
