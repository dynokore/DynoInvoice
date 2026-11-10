package com.dynokore.gst.controlPlane.adapters.persistence

import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.TENANT_SCHEDULE
import com.dynokore.gst.controlPlane.application.schedule.TenantScheduleRepository
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.schedule.CustodyHolder
import com.dynokore.gst.controlPlane.domain.schedule.PendingKind
import com.dynokore.gst.controlPlane.domain.schedule.PendingRequest
import com.dynokore.gst.controlPlane.domain.schedule.ScheduleState
import com.dynokore.gst.controlPlane.domain.schedule.TenantSchedule
import org.jooq.DSLContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Tenant schedules in the `controlplane` schema through jOOQ (TPL-11), in the open transaction of the step. */
class TenantScheduleRepositoryJooq(
    private val dsl: DSLContext,
) : TenantScheduleRepository {
    override fun find(tenantRef: CommercialRef): TenantSchedule? =
        dsl
            .selectFrom(TENANT_SCHEDULE)
            .where(TENANT_SCHEDULE.TENANT_REF.eq(tenantRef.value))
            .orderBy(
                TENANT_SCHEDULE.STATE.ne(ScheduleState.CANCELLED.name).desc(),
                TENANT_SCHEDULE.SET_AT.desc(),
            ).limit(1)
            .fetchOne()
            ?.let(::scheduleOf)

    override fun insert(
        schedule: TenantSchedule,
        setAt: Instant,
    ) {
        dsl
            .insertInto(TENANT_SCHEDULE)
            .set(TENANT_SCHEDULE.SCHEDULE_REF, schedule.scheduleRef)
            .set(TENANT_SCHEDULE.TENANT_REF, schedule.tenantRef.value)
            .set(TENANT_SCHEDULE.CONTRACT_REFERENCE, schedule.contractReference.value)
            .set(TENANT_SCHEDULE.STATE, schedule.state.name)
            .set(TENANT_SCHEDULE.GO_LIVE_AT, utc(schedule.goLiveAt))
            .set(TENANT_SCHEDULE.OFFBOARDING_AT, utc(schedule.offboardingAt))
            .set(TENANT_SCHEDULE.SET_BY, schedule.setBy)
            .set(TENANT_SCHEDULE.SET_AT, utc(setAt))
            .set(TENANT_SCHEDULE.CUSTODY_NAME, schedule.custodyHolder.name)
            .set(TENANT_SCHEDULE.CUSTODY_EMAIL, schedule.custodyHolder.contactEmail)
            .set(TENANT_SCHEDULE.CUSTODY_MOBILE, schedule.custodyHolder.contactMobile)
            .set(TENANT_SCHEDULE.NOTICE_WINDOW_DAYS, schedule.noticeWindowDays)
            .execute()
    }

    override fun update(schedule: TenantSchedule) {
        val changed =
            dsl
                .update(TENANT_SCHEDULE)
                .set(TENANT_SCHEDULE.STATE, schedule.state.name)
                .set(TENANT_SCHEDULE.GO_LIVE_AT, utc(schedule.goLiveAt))
                .set(TENANT_SCHEDULE.OFFBOARDING_AT, utc(schedule.offboardingAt))
                .set(TENANT_SCHEDULE.APPROVED_BY, schedule.approvedBy)
                .set(TENANT_SCHEDULE.OPENED_AT, schedule.openedAt?.let(::utc))
                .set(TENANT_SCHEDULE.PENDING_CHANGE_GO_LIVE_AT, schedule.pendingChange?.goLiveAt?.let(::utc))
                .set(TENANT_SCHEDULE.PENDING_CHANGE_OFFBOARDING_AT, schedule.pendingChange?.offboardingAt?.let(::utc))
                .set(TENANT_SCHEDULE.PENDING_CHANGE_BY, schedule.pendingChange?.requestedBy)
                .set(TENANT_SCHEDULE.PENDING_CANCELLATION_BY, schedule.pendingCancellation?.requestedBy)
                .where(TENANT_SCHEDULE.SCHEDULE_REF.eq(schedule.scheduleRef))
                .execute()
        check(changed == 1) { "the schedule ${schedule.scheduleRef} is gone" }
    }

    override fun dueForOpening(
        now: Instant,
        limit: Int,
    ): List<CommercialRef> =
        dsl
            .select(TENANT_SCHEDULE.TENANT_REF)
            .from(TENANT_SCHEDULE)
            .where(
                TENANT_SCHEDULE.STATE
                    .eq(ScheduleState.APPROVED.name)
                    .and(TENANT_SCHEDULE.OPENED_AT.isNull)
                    .and(TENANT_SCHEDULE.GO_LIVE_AT.le(utc(now))),
            ).orderBy(
                TENANT_SCHEDULE.LAST_OPEN_ATTEMPT_AT.asc().nullsFirst(),
                TENANT_SCHEDULE.GO_LIVE_AT.asc(),
                TENANT_SCHEDULE.SCHEDULE_REF.asc(),
            ).limit(limit)
            .fetch(TENANT_SCHEDULE.TENANT_REF)
            .map { CommercialRef(checkNotNull(it)) }

    override fun recordOpenAttempt(
        tenantRef: CommercialRef,
        at: Instant,
    ) {
        dsl
            .update(TENANT_SCHEDULE)
            .set(TENANT_SCHEDULE.LAST_OPEN_ATTEMPT_AT, utc(at))
            .where(
                TENANT_SCHEDULE.TENANT_REF
                    .eq(tenantRef.value)
                    .and(TENANT_SCHEDULE.STATE.eq(ScheduleState.APPROVED.name))
                    .and(TENANT_SCHEDULE.OPENED_AT.isNull),
            ).execute()
    }

    override fun lock(tenantRef: CommercialRef): TenantSchedule? =
        dsl
            .selectFrom(TENANT_SCHEDULE)
            .where(
                TENANT_SCHEDULE.TENANT_REF
                    .eq(tenantRef.value)
                    .and(TENANT_SCHEDULE.STATE.ne(ScheduleState.CANCELLED.name)),
            ).forUpdate()
            .skipLocked()
            .fetchOne()
            ?.let(::scheduleOf)

    private fun utc(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    private fun scheduleOf(row: org.jooq.Record): TenantSchedule {
        val r = row.into(TENANT_SCHEDULE)
        val changeBy = r.pendingChangeBy
        return TenantSchedule(
            tenantRef = CommercialRef(checkNotNull(r.tenantRef)),
            scheduleRef = checkNotNull(r.scheduleRef),
            contractReference = CommercialRef(checkNotNull(r.contractReference)),
            state = ScheduleState.valueOf(checkNotNull(r.state)),
            goLiveAt = checkNotNull(r.goLiveAt).toInstant(),
            offboardingAt = checkNotNull(r.offboardingAt).toInstant(),
            setBy = checkNotNull(r.setBy),
            approvedBy = r.approvedBy,
            custodyHolder = CustodyHolder(checkNotNull(r.custodyName), checkNotNull(r.custodyEmail), r.custodyMobile),
            noticeWindowDays = r.noticeWindowDays,
            openedAt = r.openedAt?.toInstant(),
            lastOpenAttemptAt = r.lastOpenAttemptAt?.toInstant(),
            pendingChange =
                changeBy?.let {
                    PendingRequest(
                        PendingKind.CHANGE,
                        r.pendingChangeGoLiveAt?.toInstant(),
                        r.pendingChangeOffboardingAt?.toInstant(),
                        it,
                    )
                },
            pendingCancellation =
                r.pendingCancellationBy?.let { PendingRequest(PendingKind.CANCELLATION, null, null, it) },
        )
    }
}
