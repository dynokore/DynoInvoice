package com.dynokore.gst.controlPlane.application.schedule

import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.schedule.TenantSchedule
import java.time.Instant

/** The tenant schedules, in the open transaction of the command or of the job step. */
interface TenantScheduleRepository {
    /** The current schedule of [tenantRef] (the one that is not CANCELLED, else the latest), or null. */
    fun find(tenantRef: CommercialRef): TenantSchedule?

    /** Stores a new schedule. */
    fun insert(
        schedule: TenantSchedule,
        setAt: Instant,
    )

    /** Stores the changes of an existing schedule (state, approver, times, openedAt and the pending requests). */
    fun update(schedule: TenantSchedule)

    /**
     * The tenants of APPROVED schedules not yet opened whose go-live time is [now] or earlier, at most [limit], in
     * the fair order: `lastOpenAttemptAt` ascending with never-attempted rows first, then `goLiveAt`, then
     * `scheduleRef`, so rows that keep failing do not keep later rows from being tried.
     */
    fun dueForOpening(
        now: Instant,
        limit: Int,
    ): List<CommercialRef>

    /**
     * Stores [at] as `lastOpenAttemptAt` of the schedule of [tenantRef] and nothing else. The caller runs it in a
     * transaction of its own that commits before the `openTenant` call, so the stamp survives a call that throws.
     */
    fun recordOpenAttempt(
        tenantRef: CommercialRef,
        at: Instant,
    )

    /**
     * Locks the schedule of [tenantRef] (`for update skip locked`) and returns it, or null when it is locked by
     * another run or does not exist.
     */
    fun lock(tenantRef: CommercialRef): TenantSchedule?
}

/** The answer of tenancy-svc `openTenant`. */
enum class TenantOpening {
    /** 2xx: the tenant is OPEN (a repeat answers the same). */
    OPENED,

    /** A timeout, an I/O failure or any other status: the opening did not happen and is tried again. */
    NOT_OPENED,
}

/** Calls tenancy-svc `openTenant` (control-plane-svc -> tenancy-svc, INV-API-3495) with the schedule reference. */
fun interface TenantOpener {
    fun open(
        tenantRef: CommercialRef,
        scheduleRef: String,
    ): TenantOpening
}

/** The audit entry of a step that no person performs; written in the open transaction of the step. */
fun interface ScheduleJobAudit {
    fun record(
        schedule: TenantSchedule,
        action: String,
        at: Instant,
    )
}
