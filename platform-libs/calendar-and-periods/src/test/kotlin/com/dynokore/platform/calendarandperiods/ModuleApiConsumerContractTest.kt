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
}
