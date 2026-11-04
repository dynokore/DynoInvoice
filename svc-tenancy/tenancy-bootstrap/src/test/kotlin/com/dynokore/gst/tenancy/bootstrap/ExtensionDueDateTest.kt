package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.calendar.NonBusinessDayPolicyHandler
import com.dynokore.gst.tenancy.adapters.calendar.PeriodFilingStatusHandler
import com.dynokore.gst.tenancy.adapters.http.ConstraintRefusal
import com.dynokore.gst.tenancy.application.period.DueDateCause
import com.dynokore.gst.tenancy.application.period.DueDateRecomputation
import com.dynokore.gst.tenancy.application.period.FilingFactStore
import com.dynokore.gst.tenancy.application.period.NonBusinessDayRuleReader
import com.dynokore.gst.tenancy.contract.model.CreateGroupRequest
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.platform.calendarandperiods.BusinessCalendar
import com.dynokore.platform.calendarandperiods.CalendarPack
import com.dynokore.platform.calendarandperiods.DueRule
import com.dynokore.platform.calendarandperiods.FilingFrequency
import com.dynokore.platform.calendarandperiods.NonBusinessDayRule
import com.dynokore.platform.calendarandperiods.StandardWindow
import com.dynokore.platform.configresolver.AcknowledgeRelaxationCommand
import com.dynokore.platform.configresolver.Actor
import com.dynokore.platform.configresolver.PolicyRefusal
import com.dynokore.platform.configresolver.PolicyRefused
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.RelaxationId
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SetPolicyCommand
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** A recorded extension and the date in force (INV-Q-16677, INV-Q-16808). */
internal class ExtensionDueDateTest : Vp62TestSupport() {
    @Test
    fun `INV-Q-16677 a rule date before the granted date keeps the granted date and publishes nothing`() {
        run { s ->
            s.extendTo("2026-09-01", "2026-12-15")
            s.loosen()
            assertThat(s.rows()["2026-09-01"]).isEqualTo("2026-12-15")
            assertThat(s.ruleEvents("2026-09-01")).isEmpty()
        }
    }

    @Test
    fun `INV-Q-16677 an extended period with a rule date after the granted date takes the rule date once`() {
        run { s ->
            s.extendTo("2026-09-01", "2026-11-01")
            assertThat(s.rows()["2026-09-01"]).isEqualTo("2026-11-01")
            s.loosen()
            assertThat(s.rows()["2026-09-01"]).isEqualTo("2026-11-02")
            val event = s.ruleEvents("2026-09-01").single()
            assertThat(event["oldFilingDueDate"].asString()).isEqualTo("2026-11-01")
            assertThat(event["newFilingDueDate"].asString()).isEqualTo("2026-11-02")
            assertThat(event["period"].asString()).isEqualTo(s.periodOf("2026-09-01"))
            assertThat(
                s.period
                    .listed(s.entity)
                    .single { it.periodStart.toString() == "2026-09-01" }
                    .extensionGranted,
            ).isTrue()
            assertThat(s.period.table(s.entity, "period_extension", "recorded_at")).hasSize(1)
        }
    }

    @Test
    fun `INV-Q-16677 a submission-due-date extension only does not enter the filing date`() {
        run { s ->
            s.extendTo("2026-09-01", "2026-12-15", target = Tgt.SUBMISSION_DUE_DATE)
            s.loosen()
            assertThat(s.rows()["2026-09-01"]).isEqualTo("2026-11-02")
            assertThat(s.ruleEvents("2026-09-01")).hasSize(1)
        }
    }

    @Test
    fun `INV-Q-16677 of two records with different references the greater date counts`() {
        run { s ->
            s.extendTo("2026-09-01", "2026-11-10", iras = "IRAS-EXT-A")
            s.extendTo("2026-09-01", "2026-11-05", iras = "IRAS-EXT-B")
            assertThat(s.rows()["2026-09-01"]).isEqualTo("2026-11-10")
            s.loosen()
            // the rule date 2026-11-02 is before the greatest granted date: nothing moves
            assertThat(s.rows()["2026-09-01"]).isEqualTo("2026-11-10")
            assertThat(s.ruleEvents("2026-09-01")).isEmpty()
        }
    }

    @Test
    fun `INV-Q-16808 a later record with an earlier date never moves the date back`() {
        run { s ->
            s.extendTo("2026-09-01", "2026-11-10", iras = "IRAS-EXT-A")
            s.extendTo("2026-09-01", "2026-11-05", iras = "IRAS-EXT-B")
            assertThat(s.rows()["2026-09-01"]).isEqualTo("2026-11-10")
            assertThat(s.extensionEvents("2026-09-01").map { it["newFilingDueDate"].asString() })
                .containsExactly("2026-11-10")
            assertThat(s.period.table(s.entity, "period_extension", "recorded_at")).hasSize(2)
            s.assertConverged()
        }
    }

    @Test
    fun `INV-Q-16808 an earlier record followed by a later one gives two events`() {
        run { s ->
            s.extendTo("2026-09-01", "2026-11-05", iras = "IRAS-EXT-A")
            s.extendTo("2026-09-01", "2026-11-10", iras = "IRAS-EXT-B")
            assertThat(s.rows()["2026-09-01"]).isEqualTo("2026-11-10")
            val events = s.extensionEvents("2026-09-01")
            assertThat(events.map { it["oldFilingDueDate"].asString() to it["newFilingDueDate"].asString() })
                .containsExactly("2026-10-31" to "2026-11-05", "2026-11-05" to "2026-11-10")
            s.assertConverged()
        }
    }

    @Test
    fun `INV-Q-16808 a record earlier than the rule date keeps the rule date, publishes no change and is kept`() {
        run { s ->
            s.extendTo("2026-09-01", "2026-10-20")
            assertThat(s.rows()["2026-09-01"]).isEqualTo("2026-10-31")
            assertThat(s.extensionEvents("2026-09-01")).isEmpty()
            assertThat(s.period.table(s.entity, "period_extension", "recorded_at")).hasSize(1)
            assertThat(s.period.events(PERIOD_EXTENDED)).hasSize(1)
            s.assertConverged()
        }
    }

    @Test
    fun `INV-Q-16808 a second record of the same IRAS reference with other values is refused and changes nothing`() {
        run { s ->
            s.extendTo("2026-09-01", "2026-11-10")
            val eventsBefore = s.period.env.captured.envelopes.size
            val refusal =
                assertThatThrownBy { s.extendTo("2026-09-01", "2026-11-05") }
                    .isInstanceOf(ConstraintRefusal::class.java)
            refusal.hasMessageContaining("already recorded")
            assertThat(s.rows()["2026-09-01"]).isEqualTo("2026-11-10")
            assertThat(s.period.env.captured.envelopes.size).isEqualTo(eventsBefore)
            assertThat(s.period.table(s.entity, "period_extension", "recorded_at")).hasSize(1)
            s.assertConverged()
        }
    }

    @Test
    fun `INV-Q-16808 a submission-due-date record leaves the filing date and publishes no change`() {
        run { s ->
            s.extendTo("2026-09-01", "2026-12-15", target = Tgt.SUBMISSION_DUE_DATE)
            assertThat(s.rows()["2026-09-01"]).isEqualTo("2026-10-31")
            assertThat(s.extensionEvents("2026-09-01")).isEmpty()
            s.assertConverged()
        }
    }

    @Test
    fun `INV-Q-16677 a filed extended period is untouched`() {
        run { s ->
            s.extendTo("2026-09-01", "2026-11-01")
            s.filed(s.periodOf("2026-09-01"))
            s.loosen()
            assertThat(s.rows()["2026-09-01"]).isEqualTo("2026-11-01")
            assertThat(s.ruleEvents("2026-09-01")).isEmpty()
        }
    }

    @Test
    fun `INV-Q-16677 an extension of another period is not used`() {
        run { s ->
            s.extendTo("2026-10-01", "2026-12-31")
            s.loosen()
            assertThat(s.rows()["2026-09-01"]).isEqualTo("2026-11-02")
            assertThat(s.rows()["2026-10-01"]).isEqualTo("2026-12-31")
            assertThat(s.ruleEvents("2026-09-01")).hasSize(1)
            assertThat(s.ruleEvents("2026-10-01")).isEmpty()
        }
    }
}
