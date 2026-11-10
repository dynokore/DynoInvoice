package com.dynokore.gst.controlPlane.application.schedule

import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.schedule.ScheduleState
import com.dynokore.gst.controlPlane.domain.schedule.TenantSchedule
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Instant

/**
 * The opening step of the scheduled job (INV-REQ-8100 behaviors 3 and 6): at or after the go-live time of an APPROVED
 * schedule it calls tenancy-svc `openTenant` with the `scheduleRef`, once. Each schedule runs in a transaction of its
 * own that locks the row (`for update skip locked`), calls `openTenant`, stores `openedAt` and writes the audit entry
 * of the scheduled job, so the entry commits with the step. A repeated or late run finds `openedAt` set and calls
 * nothing; a schedule that is not APPROVED, or whose time has not come, causes no call. A failed call changes nothing
 * and is tried again by the next run (tenancy-svc answers a repeat unchanged). The closing at the offboarding time is
 * INV-T-8180, not this step.
 */
class OpenDueTenants(
    private val schedules: TenantScheduleRepository,
    private val opener: TenantOpener,
    private val audit: ScheduleJobAudit,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    /** One run: returns how many `openTenant` calls it made. */
    fun runOnce(batchSize: Int): Int {
        val runAt = clock.instant()
        val due = transactions.inTransaction { schedules.dueForOpening(runAt, batchSize) }
        return due.count { openOne(it, runAt) }
    }

    private fun openOne(
        tenantRef: CommercialRef,
        runAt: Instant,
    ): Boolean {
        // The attempt is stamped in a transaction of its own that commits before the call, so a call that throws
        // still leaves the stamp and the next run takes the rows tried least recently first (fair order).
        transactions.inTransaction { schedules.recordOpenAttempt(tenantRef, runAt) }
        return openLocked(tenantRef)
    }

    private fun openLocked(tenantRef: CommercialRef): Boolean =
        transactions.inTransaction {
            val now = clock.instant()
            val schedule = schedules.lock(tenantRef)
            if (schedule == null || !isDue(schedule, now)) {
                false
            } else {
                if (opener.open(tenantRef, schedule.scheduleRef) == TenantOpening.OPENED) {
                    val opened = schedule.copy(openedAt = now)
                    schedules.update(opened)
                    audit.record(opened, ACTION, now)
                }
                true
            }
        }

    private fun isDue(
        schedule: TenantSchedule,
        now: Instant,
    ) = schedule.state == ScheduleState.APPROVED && schedule.openedAt == null && !schedule.goLiveAt.isAfter(now)

    companion object {
        /** The actor of the audit entry: the scheduled job, not a person. */
        const val ACTOR = "job:tenant-schedule-opening"
        const val ACTION = "openTenant"
    }
}
