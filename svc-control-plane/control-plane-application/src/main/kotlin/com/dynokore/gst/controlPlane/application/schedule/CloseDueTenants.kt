package com.dynokore.gst.controlPlane.application.schedule

import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.schedule.ScheduleState
import com.dynokore.gst.controlPlane.domain.schedule.TenantSchedule
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Instant

/**
 * The closing step of the scheduled job (INV-REQ-8100 behaviors 3 and 5, owner answer INV-Q-8137): at or after the
 * offboarding time of an APPROVED schedule it calls tenancy-svc `closeTenant`, once. Each schedule runs in a
 * transaction of its own that locks the row (`for update skip locked`), calls `closeTenant`, stores the new state
 * and writes the audit entry of the scheduled job, so the entry commits with the step (INV-BR-1894).
 *
 * - 202: the schedule becomes EXECUTED; a repeated or late run finds it EXECUTED and calls nothing.
 * - a refusal (409 of a failed offboarding check) or any failure: the tenant is left as it is, the schedule becomes
 *   FAILED and the audit entry carries the refusal `detail`; `getTenantSchedule` then reads FAILED.
 * - a schedule whose opening was not carried out: no call, FAILED and an audit entry (a tenant that was never opened
 *   has no closing; `closeTenant` refuses a tenant that is not OPEN).
 * - a schedule that is not APPROVED, or whose offboarding time has not come: no call, nothing changes.
 */
class CloseDueTenants(
    private val schedules: TenantScheduleRepository,
    private val closer: TenantCloser,
    private val audit: ScheduleClosingAudit,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    /** One run: returns how many `closeTenant` calls it made. */
    fun runOnce(batchSize: Int): Int {
        val runAt = clock.instant()
        val due = transactions.inTransaction { schedules.dueForClosing(runAt, batchSize) }
        return due.count { closeLocked(it) }
    }

    private fun closeLocked(tenantRef: CommercialRef): Boolean =
        transactions.inTransaction {
            val now = clock.instant()
            val schedule = schedules.lock(tenantRef)
            when {
                schedule == null || !isDue(schedule, now) -> {
                    false
                }

                schedule.openedAt == null -> {
                    fail(schedule, now, NOT_OPENED_DETAIL)
                    false
                }

                else -> {
                    when (val answer = closer.close(tenantRef, schedule)) {
                        TenantClosing.Closed -> {
                            val executed = schedule.copy(state = ScheduleState.EXECUTED)
                            schedules.update(executed)
                            audit.record(executed, ACTION, now, null)
                        }

                        is TenantClosing.NotClosed -> {
                            fail(schedule, now, answer.detail)
                        }
                    }
                    true
                }
            }
        }

    private fun fail(
        schedule: TenantSchedule,
        now: Instant,
        detail: String,
    ) {
        val failed = schedule.copy(state = ScheduleState.FAILED)
        schedules.update(failed)
        audit.record(failed, ACTION_FAILED, now, detail)
    }

    private fun isDue(
        schedule: TenantSchedule,
        now: Instant,
    ) = schedule.state == ScheduleState.APPROVED && !schedule.offboardingAt.isAfter(now)

    companion object {
        const val ACTION = "closeTenant"
        const val ACTION_FAILED = "closeTenantFailed"
        const val NOT_OPENED_DETAIL = "the opening of the tenant was not carried out"
    }
}
