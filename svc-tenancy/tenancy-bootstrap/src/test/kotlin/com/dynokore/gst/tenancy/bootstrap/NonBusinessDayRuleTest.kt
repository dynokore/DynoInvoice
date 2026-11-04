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

/**
 * The non-business-day rule VP-62 (INV-REQ-82, INV-REQ-1323, INV-Q-16111): set through the shared setPolicy with the
 * acknowledgements config-resolver demands; tenancy-svc consumes PolicyChanged and recomputes the unfiled periods of
 * the entity whose filing due date is on or after the effective date, one PeriodDueDateChanged per changed date.
 */
internal class NonBusinessDayRuleTest : Vp62TestSupport() {
    @Test
    fun `INV-AC-16066 INV-AC-200 the acknowledged looser option recomputes unfiled periods from its date`() {
        run { s ->
            val before = s.dueDates()
            assertThat(before["2026-06-01"]).isEqualTo("2026-08-01")
            assertThat(before["2026-09-01"]).isEqualTo("2026-10-31")
            assertThat(before["2026-11-01"]).isEqualTo("2026-12-05")
            val periodOfSeptember =
                s.period
                    .records(s.entity)
                    .single { it.start.toString() == "2026-09-01" }
                    .periodRef
            s.filed(
                s.period
                    .records(s.entity)
                    .single { it.start.toString() == "2026-11-01" }
                    .periodRef,
            )
            val scope = s.scope()
            // before the acknowledgement the same call is refused: nothing moves and nothing is published
            assertThat(s.refusal { s.set(scope, ScopeLevel.ENTITY, null) }).isEqualTo(PolicyRefusal.RELAXATION_REQUIRED)
            assertThat(s.dueDates()).isEqualTo(before)
            assertThat(changedDates()(s)).isEmpty()
            val status = s.acknowledge(scope, ScopeLevel.ENTITY, "user:approver", "tax.approver")
            assertThat(status.outstandingRoles).isEmpty()
            s.set(scope, ScopeLevel.ENTITY, status.relaxation)
            val changed = s.policyChanges().last()
            assertThat(s.deliver(changed.dataJson)).isEqualTo("APPLIED")
            val after = s.dueDates()
            assertThat(after["2026-09-01"]).isEqualTo("2026-11-02")
            assertThat(after["2026-06-01"]).isEqualTo("2026-08-01")
            assertThat(after["2026-11-01"]).isEqualTo("2026-12-05")
            val event = changedDates()(s).single()
            assertThat(event["oldFilingDueDate"].asString()).isEqualTo("2026-10-31")
            assertThat(event["newFilingDueDate"].asString()).isEqualTo("2026-11-02")
            assertThat(event["period"].asString()).isEqualTo(periodOfSeptember)
            assertThat(event["cause"]["variationPoint"].asString()).isEqualTo("VP-62")
            // the same event delivered twice changes nothing more
            assertThat(s.deliver(changed.dataJson, changed.id.value)).isEqualTo("IGNORED")
            assertThat(changedDates()(s)).hasSize(1)
        }
    }

    @Test
    fun `INV-AC-205 the looser option needs the tax approver, and the client signatory under an agent`() {
        run { s ->
            val own = s.scope()
            assertThat(s.refusal { s.set(own, ScopeLevel.ENTITY, null) }).isEqualTo(PolicyRefusal.RELAXATION_REQUIRED)
            val client = s.scope(client = true)
            val first = s.acknowledge(client, ScopeLevel.CLIENT_ENTITY, "user:approver", "tax.approver")
            assertThat(first.outstandingRoles).containsExactly("client.signatory")
            assertThat(s.refusal { s.set(client, ScopeLevel.CLIENT_ENTITY, first.relaxation) })
                .isEqualTo(PolicyRefusal.RELAXATION_REQUIRED)
            assertThat(s.dueDates()["2026-09-01"]).isEqualTo("2026-10-31")
            assertThat(changedDates()(s)).isEmpty()
        }
    }

    @Test
    fun `INV-AC-2392 INV-AC-2311 one agent client moving to the next working day leaves the other client unchanged`() {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            env.withGroup(2, DIVISIONAL) { tenant, group, members ->
                val period = PeriodEnv(env)
                period.calendar.pack = saturdayPack()
                members.forEach {
                    period.register(it, "2026-01-01")
                    period.change(it, Freq.MONTHLY, "2026-01-01")
                }
                val (a, b) = members
                val s = Setup(period, tenant, a, group.groupRef)
                val scope = s.scope(a.entityRef, client = true)
                val first = s.acknowledge(scope, ScopeLevel.CLIENT_ENTITY, "user:approver", "tax.approver")
                s.acknowledge(scope, ScopeLevel.CLIENT_ENTITY, "user:signatory", "client.signatory", first.relaxation)
                s.set(scope, ScopeLevel.CLIENT_ENTITY, first.relaxation)
                assertThat(s.deliver(s.policyChanges().last().dataJson)).isEqualTo("APPLIED")

                fun due(e: EntityResponse) =
                    period
                        .records(e)
                        .single { it.start == day("2026-09-01") }
                        .dueDate
                        .toString()
                assertThat(due(a)).isEqualTo("2026-11-02")
                assertThat(due(b)).isEqualTo("2026-10-31")
                val changed = period.events(PERIOD_DUE_DATE_CHANGED)
                assertThat(changed.map { it["entityRef"].asString() }.toSet()).containsExactly(a.entityRef)
            }
        }
    }

    @Test
    fun `INV-AC-200 a rule pinned by the calendar pack applies whatever VP-62 says`() {
        run { s ->
            s.period.calendar.pack = saturdayPack(pinned = NonBusinessDayRule.NEXT_WORKING_DAY)
            val cause =
                "{\"scope\":\"ENTITY:${s.entity.entityRef}\",\"vpId\":\"VP-62\"," +
                    "\"effectiveDate\":\"2026-09-01\",\"source\":\"OVERRIDE\"}"
            assertThat(s.deliver(cause)).isEqualTo("APPLIED")
            assertThat(s.dueDates()["2026-09-01"]).isEqualTo("2026-11-02")
        }
    }

    @Test
    fun `an event that is not VP-62 at entity or client level, or names another entity, recomputes nothing`() {
        run { s ->
            fun payload(
                vp: String = "VP-62",
                scope: String = "ENTITY:${s.entity.entityRef}",
            ) = "{\"scope\":\"$scope\",\"vpId\":\"$vp\",\"effectiveDate\":\"2026-09-01\",\"source\":\"OVERRIDE\"}"
            s.period.calendar.pack = saturdayPack(pinned = NonBusinessDayRule.NEXT_WORKING_DAY)
            val before = s.dueDates()
            listOf(
                "VP-620" to payload(vp = "VP-620"),
                "vp-62" to payload(vp = "vp-62"),
                "VP-62 " to payload(vp = "VP-62 "),
                "tenant scope" to payload(scope = "TENANT:${s.tenant.value}"),
                "group scope" to payload(scope = "GROUP:${s.group}"),
                "group scope naming the entity" to payload(scope = "GROUP:${s.entity.entityRef}"),
                "tenant scope naming the entity" to payload(scope = "TENANT:${s.entity.entityRef}"),
                "feed scope naming the entity" to payload(scope = "FEED:${s.entity.entityRef}"),
                "installation" to payload(scope = "INSTALLATION"),
                "entity ref prefix" to payload(scope = "ENTITY:${s.entity.entityRef.dropLast(1)}"),
                "entity ref extended" to payload(scope = "ENTITY:${s.entity.entityRef}X"),
                "level look-alike" to payload(scope = "ENTITYX:${s.entity.entityRef}"),
            ).forEach { (what, data) ->
                assertThat(s.deliver(data)).describedAs(what).isEqualTo("IGNORED")
                assertThat(s.dueDates()).describedAs(what).isEqualTo(before)
            }
            assertThat(changedDates()(s)).isEmpty()
        }
    }

    @Test
    fun `INV-Q-16812 neither an entity nor a client entity value gives NO_MOVE`() {
        run { s -> assertThat(s.ruleOf(s.entity.entityRef)).isEqualTo(NonBusinessDayRule.NO_MOVE) }
    }

    @Test
    fun `INV-Q-16812 an entity value alone is read`() {
        run { s ->
            val scope = s.scope()
            s.set(
                scope,
                ScopeLevel.ENTITY,
                s.acknowledge(scope, ScopeLevel.ENTITY, "user:approver", "tax.approver").relaxation,
            )
            assertThat(s.ruleOf(s.entity.entityRef)).isEqualTo(NonBusinessDayRule.NEXT_WORKING_DAY)
        }
    }

    @Test
    fun `INV-Q-16812 the client entity value of the same entity wins over the entity value`() {
        run { s ->
            val own = s.scope()
            s.set(
                own,
                ScopeLevel.ENTITY,
                s.acknowledge(own, ScopeLevel.ENTITY, "user:approver", "tax.approver").relaxation,
            )
            assertThat(s.ruleOf(s.entity.entityRef)).isEqualTo(NonBusinessDayRule.NEXT_WORKING_DAY)
            s.set(s.scope(client = true), ScopeLevel.CLIENT_ENTITY, null, option = keep)
            assertThat(s.ruleOf(s.entity.entityRef)).isEqualTo(NonBusinessDayRule.NO_MOVE)
        }
    }

    @Test
    fun `INV-Q-16812 a client entity value for another client reference is not used`() {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            env.withGroup(2, DIVISIONAL) { tenant, group, members ->
                val period = PeriodEnv(env)
                period.calendar.pack = saturdayPack()
                val (a, b) = members
                val s = Setup(period, tenant, a, group.groupRef)
                val scope = s.scope(b.entityRef, client = true)
                val first = s.acknowledge(scope, ScopeLevel.CLIENT_ENTITY, "user:approver", "tax.approver")
                s.acknowledge(scope, ScopeLevel.CLIENT_ENTITY, "user:signatory", "client.signatory", first.relaxation)
                s.set(scope, ScopeLevel.CLIENT_ENTITY, first.relaxation)
                assertThat(s.ruleOf(b.entityRef)).isEqualTo(NonBusinessDayRule.NEXT_WORKING_DAY)
                assertThat(s.ruleOf(a.entityRef)).isEqualTo(NonBusinessDayRule.NO_MOVE)
            }
        }
    }
}
