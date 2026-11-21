package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.platform.configresolver.PolicyRefusal
import com.dynokore.platform.configresolver.ScopeLevel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Tenancy half of INV-AC-206 (INV-REQ-82): an acknowledged next-working-day option VP-62 moves a Saturday filing due
 * date to Monday and publishes the old and the new date; before the acknowledgement the setting is refused.
 */
internal class NonBusinessDayNextWorkingDayTest : Vp62TestSupport() {
    @Test
    @Suppress("ktlint:standard:max-line-length", "MaxLineLength")
    fun `INV-AC-26246 an acknowledged next working day moves the Saturday filing due date to Monday and publishes the old and new date`() {
        run { s ->
            // (1) before the setting the due date is a Saturday
            val before = s.dueDates()
            assertThat(before["2026-09-01"]).isEqualTo("2026-10-31")
            assertThat(LocalDate.parse("2026-10-31").dayOfWeek).isEqualTo(DayOfWeek.SATURDAY)

            // (2) without the acknowledgement the setting is refused and nothing moves or is published
            val scope = s.scope()
            assertThat(s.refusal { s.set(scope, ScopeLevel.ENTITY, null) }).isEqualTo(PolicyRefusal.RELAXATION_REQUIRED)
            assertThat(s.dueDates()).isEqualTo(before)
            assertThat(changedDates()(s)).isEmpty()

            // (3) acknowledged and set; the delivered PolicyChanged moves the date to the Monday
            val status = s.acknowledge(scope, ScopeLevel.ENTITY, "user:approver", "tax.approver")
            assertThat(status.outstandingRoles).isEmpty()
            s.set(scope, ScopeLevel.ENTITY, status.relaxation)
            val changed = s.policyChanges().last()
            assertThat(s.deliver(changed.dataJson)).isEqualTo("APPLIED")
            val after = s.dueDates()
            assertThat(after["2026-09-01"]).isEqualTo("2026-11-02")
            assertThat(LocalDate.parse("2026-11-02").dayOfWeek).isEqualTo(DayOfWeek.MONDAY)

            // (4) exactly one event names the period with the old and the new date and the cause VP-62
            val event = s.ruleEvents("2026-09-01").single()
            assertThat(event["oldFilingDueDate"].asString()).isEqualTo("2026-10-31")
            assertThat(event["newFilingDueDate"].asString()).isEqualTo("2026-11-02")
            assertThat(event["period"].asString()).isEqualTo(s.periodOf("2026-09-01"))
            assertThat(event["cause"]["variationPoint"].asString()).isEqualTo("VP-62")

            // (5) a Saturday before the effective date keeps its date
            assertThat(after["2026-06-01"]).isEqualTo("2026-08-01")
        }
    }
}
