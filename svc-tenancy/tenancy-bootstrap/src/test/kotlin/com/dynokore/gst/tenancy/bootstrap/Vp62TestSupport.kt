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

internal val DIVISIONAL = CreateGroupRequest.GroupKind.DIVISIONAL

/** The shared fixture and helpers of the VP-62 and record-time due date tests (INV-REQ-82, INV-Q-16808). */
internal open class Vp62TestSupport {
    protected val vp62 = VariationPointKey("VP-62")
    protected val move = listOf(SelectedOption("NEXT_WORKING_DAY", null))

    /** Monthly windows of 2026 to November; June, September, November are due on Saturdays (08-01, 10-31, 12-05). */
    protected fun saturdayPack(pinned: NonBusinessDayRule? = null): CalendarPack {
        val forms = mapOf(6 to "FA", 9 to "FB", 11 to "FC")
        val windows =
            (1..11).map {
                val month = YearMonth.of(2026, it)
                StandardWindow(month.atDay(1), month.atEndOfMonth(), forms[it] ?: "F5")
            }
        return CalendarPack(
            "saturday-1",
            mapOf(FilingFrequency("MONTHLY") to windows),
            "F8",
            mapOf(
                "F5" to DueRule(1, true, 0),
                "F8" to DueRule(3, true, 0),
                "FA" to DueRule(0, false, 32),
                "FB" to DueRule(0, false, 31),
                "FC" to DueRule(0, false, 5),
            ),
            BusinessCalendar(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), emptySet()),
            pinned,
        )
    }

    internal class Setup(
        val period: PeriodEnv,
        val tenant: TenantId,
        val entity: EntityResponse,
        val group: String,
    ) {
        val handler =
            NonBusinessDayPolicyHandler(
                period.env.context.getBean(DueDateRecomputation::class.java),
                ObjectMapper(),
                "VP-62",
            )

        fun dueDates(): Map<String, String> =
            period.records(entity).associate { it.start.toString() to it.dueDate.toString() }

        fun policyChanges() =
            period.env.captured.envelopes
                .filter { it.type.endsWith("PolicyChanged.v1") }

        fun deliver(
            data: String,
            id: String = UUID.randomUUID().toString(),
        ) = period.deliver(tenant, handler, "com.dynokore.gst.platform.PolicyChanged.v1", data, id)
    }

    protected fun run(block: (Setup) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            env.withGroup(1, DIVISIONAL) { tenant, group, members ->
                val period = PeriodEnv(env)
                period.calendar.pack = saturdayPack()
                val entity = members.single()
                period.register(entity, "2026-01-01")
                period.change(entity, Freq.MONTHLY, "2026-01-01")
                block(Setup(period, tenant, entity, group.groupRef))
            }
        }
    }

    protected fun Setup.scope(
        ref: String = entity.entityRef,
        client: Boolean = false,
    ) = PolicyScope.entity(tenant, ref, group = group, clientEntity = if (client) ref else null)

    protected fun Setup.acknowledge(
        scope: PolicyScope,
        level: ScopeLevel,
        person: String,
        role: String,
        relaxation: RelaxationId? = null,
    ) = period.env.inTx {
        period.env.writer.acknowledgeControlRelaxation(
            AcknowledgeRelaxationCommand(
                vp62,
                scope,
                level,
                move,
                day("2026-09-01"),
                Actor(person, setOf(role)),
                role,
                CorrelationId("corr-${UUID.randomUUID()}"),
                CausationId("cause-${UUID.randomUUID()}"),
                relaxation,
            ),
        )
    }

    protected fun Setup.set(
        scope: PolicyScope,
        level: ScopeLevel,
        relaxation: RelaxationId?,
        effective: LocalDate = day("2026-09-01"),
        option: List<SelectedOption> = move,
    ) = period.env.inTx {
        period.env.writer.setPolicy(
            SetPolicyCommand(
                vp62,
                scope,
                level,
                option,
                effective,
                "test",
                Actor("user:setter", setOf("tax.approver")),
                CorrelationId("corr-${UUID.randomUUID()}"),
                CausationId("cause-${UUID.randomUUID()}"),
                relaxation = relaxation,
            ),
        )
    }

    protected fun Setup.refusal(block: () -> Unit): PolicyRefusal {
        val thrown = assertThatThrownBy { block() }
        thrown.isInstanceOf(PolicyRefused::class.java)
        return (thrown.actual() as PolicyRefused).refusal
    }

    protected fun Setup.filed(periodRef: String) {
        val facts = period.env.context.getBean(FilingFactStore::class.java)
        val handler = PeriodFilingStatusHandler(facts, ObjectMapper())
        val payload =
            "{\"entityRef\":\"${entity.entityRef}\",\"period\":\"$periodRef\",\"submissionLate\":false," +
                "\"recordedAt\":\"2026-11-20T04:00:00Z\",\"governingFiledAt\":\"2026-11-20T03:00:00Z\"}"
        period.deliver(tenant, handler, "com.dynokore.gst.submission.PeriodFilingStatusChanged.v1", payload)
    }

    protected fun changedDates() = { s: Setup -> s.period.events(PERIOD_DUE_DATE_CHANGED) }

    /** The looser option acknowledged, set from 2026-09-01 and delivered as the PolicyChanged event. */
    protected fun Setup.loosen() {
        val scope = scope()
        val status = acknowledge(scope, ScopeLevel.ENTITY, "user:approver", "tax.approver")
        set(scope, ScopeLevel.ENTITY, status.relaxation)
        val changed = policyChanges().last()
        assertThat(deliver(changed.dataJson)).isEqualTo("APPLIED")
        assertThat(deliver(changed.dataJson, changed.id.value)).isEqualTo("IGNORED")
    }

    protected fun Setup.periodOf(start: String) =
        period.records(entity).single { it.start.toString() == start }.periodRef

    protected fun Setup.rows() = period.records(entity).associate { it.start.toString() to it.dueDate.toString() }

    /** The VP-62 PeriodDueDateChanged events of the period starting on [start]. */
    protected fun Setup.ruleEvents(start: String) =
        changedDates()(this).filter {
            it["cause"]["variationPoint"] != null && it["period"].asString() == periodOf(start)
        }

    protected fun Setup.extendTo(
        start: String,
        date: String,
        iras: String = "IRAS-EXT-1",
        target: Tgt = Tgt.FILING_DUE_DATE,
    ) {
        period.actAs(entity, "tax.approver")
        val request = Req(day(date), iras, "DOC-1", target)
        period.extend(this.entity, periodOf(start), request)
    }

    /** The recomputation from the start publishes nothing further: the stored dates are the dates in force. */
    protected fun Setup.assertConverged() {
        val recomputation = period.env.context.getBean(DueDateRecomputation::class.java)
        val cause = DueDateCause.VariationPoint("VP-62", day("2026-01-01"))
        val changes =
            period.env.inTenant(tenant) {
                period.env.inTx { recomputation.applyTo(entity.entityRef, day("2026-01-01"), cause) }
            }
        assertThat(changes).isEmpty()
    }

    protected fun Setup.extensionEvents(start: String) =
        changedDates()(
            this,
        ).filter { it["cause"]["irasReference"] != null && it["period"].asString() == periodOf(start) }

    protected val keep = listOf(SelectedOption("NO_MOVE", null))

    /** VP-62 as the period planner reads it for the entity on a date after the policy dates (INV-Q-16812). */
    protected fun Setup.ruleOf(entityRef: String): NonBusinessDayRule =
        period.env.inTenant(tenant) {
            period.env.inTx {
                period.env.context
                    .getBean(NonBusinessDayRuleReader::class.java)
                    .resolve(group, entityRef, day("2026-09-02"))
            }
        }
}
