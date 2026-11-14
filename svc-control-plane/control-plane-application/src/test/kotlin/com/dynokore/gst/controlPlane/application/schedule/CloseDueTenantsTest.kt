package com.dynokore.gst.controlPlane.application.schedule

import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.schedule.CustodyHolder
import com.dynokore.gst.controlPlane.domain.schedule.ScheduleState
import com.dynokore.gst.controlPlane.domain.schedule.TenantSchedule
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** The closing step of the scheduled job against a controllable clock, a closeTenant stub and fakes (INV-T-8180). */
@Suppress("MaxLineLength", "ktlint:standard:max-line-length")
class CloseDueTenantsTest {
    private val goLive = Instant.parse("2026-12-01T01:00:00Z")
    private val offboarding = Instant.parse("2027-12-01T01:00:00Z")
    private var now = offboarding
    private val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?) = this

            override fun instant() = now
        }
    private val rows = LinkedHashMap<CommercialRef, TenantSchedule>()
    private val repository =
        object : TenantScheduleRepository {
            override fun find(tenantRef: CommercialRef) = rows[tenantRef]

            override fun insert(
                schedule: TenantSchedule,
                setAt: Instant,
            ) {
                rows[schedule.tenantRef] = schedule
            }

            override fun update(schedule: TenantSchedule) {
                rows[schedule.tenantRef] = schedule
            }

            override fun dueForOpening(
                now: Instant,
                limit: Int,
            ) = emptyList<CommercialRef>()

            override fun dueForClosing(
                now: Instant,
                limit: Int,
            ) = rows.values
                .filter { it.state == ScheduleState.APPROVED && !it.offboardingAt.isAfter(now) }
                .sortedBy { it.scheduleRef }
                .take(limit)
                .map { it.tenantRef }

            override fun recordOpenAttempt(
                tenantRef: CommercialRef,
                at: Instant,
            ) = Unit

            override fun lock(tenantRef: CommercialRef) = find(tenantRef)
        }
    private val calls = ArrayList<TenantSchedule>()
    private val refusals = HashMap<String, String>()
    private val tenantState = HashMap<String, String>()
    private val audits = ArrayList<List<Any?>>()
    private val step =
        CloseDueTenants(
            repository,
            { t, s ->
                calls += s
                refusals[t.value]?.let { TenantClosing.NotClosed(it) } ?: TenantClosing.Closed.also {
                    tenantState[t.value] = "CLOSED"
                }
            },
            { s, a, at, d -> audits += listOf(s.tenantRef.value, s.contractReference.value, a, at, d) },
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            },
            clock,
        )

    private fun schedule(
        tenant: String,
        ref: String,
        state: ScheduleState = ScheduleState.APPROVED,
        opened: Boolean = true,
        notice: Int? = 30,
    ): CommercialRef {
        val t = CommercialRef(tenant)
        tenantState[tenant] = "OPEN"
        rows[t] =
            TenantSchedule(
                t,
                ref,
                CommercialRef("CTR-$ref"),
                state,
                goLive,
                offboarding,
                "alice",
                "bob",
                CustodyHolder("Custodian", "c@x.example", null),
                notice,
                if (opened) goLive else null,
            )
        return t
    }

    @Test
    fun `INV-AC-8102 probe one second before the offboarding time causes no call, two runs at it cause one call`() {
        val t = schedule("TEN-1", "SCH-1")
        now = offboarding.minusSeconds(1)
        step.runOnce(10) shouldBe 0
        calls.size shouldBe 0
        now = offboarding
        step.runOnce(10) shouldBe 1
        step.runOnce(10) shouldBe 0
        now = offboarding.plusSeconds(3600)
        step.runOnce(10) shouldBe 0
        calls.single().let { it.scheduleRef to it.custodyHolder.name } shouldBe ("SCH-1" to "Custodian")
        calls.single().noticeWindowDays shouldBe 30
        rows.getValue(t).state shouldBe ScheduleState.EXECUTED
        audits.single() shouldBe listOf("TEN-1", "CTR-SCH-1", "closeTenant", offboarding, null)
    }

    @Test
    fun `INV-AC-8102 probe an AWAITING_APPROVAL schedule causes no call and nothing changes`() {
        val t = schedule("TEN-1", "SCH-1", ScheduleState.AWAITING_APPROVAL)
        step.runOnce(10) shouldBe 0
        calls.size shouldBe 0
        audits.size shouldBe 0
        rows.getValue(t).state shouldBe ScheduleState.AWAITING_APPROVAL
    }

    @Test
    fun `INV-AC-8102 probe a stub that refuses leaves the schedule FAILED and the tenant OPEN with the detail audited`() {
        val t = schedule("TEN-2", "SCH-2")
        refusals["TEN-2"] = "an in-scope document is unsubmitted"
        step.runOnce(10) shouldBe 1
        step.runOnce(10) shouldBe 0
        rows.getValue(t).state shouldBe ScheduleState.FAILED
        tenantState["TEN-2"] shouldBe "OPEN"
        audits.single() shouldBe
            listOf("TEN-2", "CTR-SCH-2", "closeTenantFailed", offboarding, "an in-scope document is unsubmitted")
    }

    @Test
    fun `INV-AC-8102 probe a schedule whose opening was not carried out causes no call and is FAILED with an audit entry`() {
        val t = schedule("TEN-3", "SCH-3", opened = false)
        step.runOnce(10) shouldBe 0
        calls.size shouldBe 0
        rows.getValue(t).state shouldBe ScheduleState.FAILED
        audits.single()[2] shouldBe "closeTenantFailed"
        audits.single()[4] shouldBe CloseDueTenants.NOT_OPENED_DETAIL
    }

    @Test
    fun `INV-AC-8177 the service closes the tenant that meets its checks once and the tenant that fails its check stays OPEN`() {
        val ok = schedule("TEN-1", "SCH-1")
        val bad = schedule("TEN-2", "SCH-2")
        refusals["TEN-2"] = "an in-scope document is unsubmitted"
        step.runOnce(10) shouldBe 2
        step.runOnce(10) shouldBe 0
        rows.getValue(ok).state shouldBe ScheduleState.EXECUTED
        rows.getValue(bad).state shouldBe ScheduleState.FAILED
        tenantState shouldBe mapOf("TEN-1" to "CLOSED", "TEN-2" to "OPEN")
        calls.map { it.scheduleRef } shouldBe listOf("SCH-1", "SCH-2")
    }

    @Test
    fun `INV-AC-8102 probe an opener that throws does not keep a due schedule from being closed and the failure is rethrown`() {
        val a = schedule("TEN-1", "SCH-1", opened = false)
        val b = schedule("TEN-2", "SCH-2")
        val opening =
            OpenDueTenants(
                object : TenantScheduleRepository by repository {
                    override fun dueForOpening(
                        now: Instant,
                        limit: Int,
                    ) = listOf(a)
                },
                { _, _ -> error("openTenant down") },
                { _, _, _ -> },
                object : TransactionRunner {
                    override fun <T> inTransaction(block: () -> T): T = block()
                },
                clock,
            )
        val thrown = runCatching { ScheduleJobRun.run(opening, step, 10) }.exceptionOrNull()
        thrown?.message shouldBe "openTenant down"
        calls.map { it.scheduleRef } shouldBe listOf("SCH-2")
        rows.getValue(b).state shouldBe ScheduleState.EXECUTED
    }
}
