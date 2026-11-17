package com.dynokore.gst.access.adapters.consultant

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.CONSULTANT_GRANT_REQUEST
import com.dynokore.gst.access.application.consultant.ConsultantGrantRequests
import com.dynokore.gst.access.domain.consultant.ConsultantGrantAsk
import com.dynokore.gst.access.domain.consultant.ConsultantGrantRecord
import com.dynokore.gst.access.domain.consultant.ConsultantRequestStatus
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset
import com.dynokore.gst.access.adapters.persistence.jooq.tables.records.ConsultantGrantRequestRecord as Row

/** TPL-11. The listeners of the DSLContext add the bound tenant to every statement; row-level security stays behind. */
class JooqConsultantGrantRequests(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : ConsultantGrantRequests {
    override fun findPending(ask: ConsultantGrantAsk): ConsultantGrantRecord? =
        dsl
            .selectFrom(CONSULTANT_GRANT_REQUEST)
            .where(CONSULTANT_GRANT_REQUEST.STATUS.eq(ConsultantRequestStatus.PENDING.name))
            .and(CONSULTANT_GRANT_REQUEST.IDENTITY_REF.eq(ask.identity.value))
            .and(CONSULTANT_GRANT_REQUEST.GO_LIVE_DATE.eq(ask.goLiveDate))
            .fetchOne()
            ?.let { toRecord(it) }

    override fun insert(ask: ConsultantGrantAsk): ConsultantGrantRecord {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound: a change runs in a bound transaction" }
        val ref =
            "CGR-" +
                ids
                    .next(clock.instant())
                    .toString()
                    .replace("-", "")
                    .takeLast(REF_DIGITS)
        dsl
            .insertInto(CONSULTANT_GRANT_REQUEST)
            .set(CONSULTANT_GRANT_REQUEST.ID, ids.next(clock.instant()))
            .set(CONSULTANT_GRANT_REQUEST.REQUEST_REF, ref)
            .set(CONSULTANT_GRANT_REQUEST.IDENTITY_REF, ask.identity.value)
            .set(CONSULTANT_GRANT_REQUEST.GO_LIVE_DATE, ask.goLiveDate)
            .set(CONSULTANT_GRANT_REQUEST.REQUESTED_BY, ask.requestedBy.value)
            .set(CONSULTANT_GRANT_REQUEST.STATUS, ConsultantRequestStatus.PENDING.name)
            .set(CONSULTANT_GRANT_REQUEST.CREATED_AT, now)
            .set(CONSULTANT_GRANT_REQUEST.CREATED_BY, actor)
            .set(CONSULTANT_GRANT_REQUEST.UPDATED_AT, now)
            .set(CONSULTANT_GRANT_REQUEST.UPDATED_BY, actor)
            .set(CONSULTANT_GRANT_REQUEST.VERSION, 0L)
            .execute()
        return checkNotNull(lock(ref)) { "the request just written is not readable" }
    }

    override fun lock(ref: String): ConsultantGrantRecord? =
        dsl
            .selectFrom(CONSULTANT_GRANT_REQUEST)
            .where(CONSULTANT_GRANT_REQUEST.REQUEST_REF.eq(ref))
            .forUpdate()
            .fetchOne()
            ?.let { toRecord(it) }

    override fun update(
        before: ConsultantGrantRecord,
        after: ConsultantGrantRecord,
    ) {
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound: a change runs in a bound transaction" }
        val changed =
            dsl
                .update(CONSULTANT_GRANT_REQUEST)
                .set(CONSULTANT_GRANT_REQUEST.STATUS, after.status.name)
                .set(CONSULTANT_GRANT_REQUEST.DECIDED_BY, after.decidedBy?.value)
                .set(CONSULTANT_GRANT_REQUEST.GRANT_REF, after.grant?.value)
                .set(CONSULTANT_GRANT_REQUEST.VALID_TO, after.validTo)
                .set(CONSULTANT_GRANT_REQUEST.UPDATED_AT, clock.instant().atOffset(ZoneOffset.UTC))
                .set(CONSULTANT_GRANT_REQUEST.UPDATED_BY, actor)
                .set(CONSULTANT_GRANT_REQUEST.VERSION, after.version)
                .where(CONSULTANT_GRANT_REQUEST.REQUEST_REF.eq(before.ref))
                .and(CONSULTANT_GRANT_REQUEST.VERSION.eq(before.version))
                .execute()
        check(changed == 1) { "the request ${before.ref} changed since it was read" }
    }

    private fun toRecord(row: Row) =
        ConsultantGrantRecord(
            ref = checkNotNull(row.requestRef),
            ask =
                ConsultantGrantAsk(
                    TenantRef(checkNotNull(row.tenantId).toString()),
                    IdentityRef(checkNotNull(row.identityRef)),
                    checkNotNull(row.goLiveDate),
                    IdentityRef(checkNotNull(row.requestedBy)),
                ),
            status = ConsultantRequestStatus.valueOf(checkNotNull(row.status)),
            decidedBy = row.decidedBy?.let { IdentityRef(it) },
            grant = row.grantRef?.let { GrantRef(it) },
            validTo = row.validTo,
            version = checkNotNull(row.version),
        )

    private companion object {
        const val REF_DIGITS = 12
    }
}
