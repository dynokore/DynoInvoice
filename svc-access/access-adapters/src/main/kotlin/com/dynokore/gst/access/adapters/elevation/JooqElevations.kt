package com.dynokore.gst.access.adapters.elevation

import com.dynokore.gst.access.adapters.persistence.jooq.tables.records.ElevationRecord as ElevationRow
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.ELEVATION
import com.dynokore.gst.access.application.elevation.Elevations
import com.dynokore.gst.access.domain.elevation.ElevationAsk
import com.dynokore.gst.access.domain.elevation.ElevationKind
import com.dynokore.gst.access.domain.elevation.ElevationRecord
import com.dynokore.gst.access.domain.elevation.ElevationStatus
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset

/** TPL-11. The listeners of the DSLContext add the bound tenant to every statement; row-level security stays behind. */
class JooqElevations(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : Elevations {
    override fun findPending(ask: ElevationAsk): ElevationRecord? =
        dsl
            .selectFrom(ELEVATION)
            .where(ELEVATION.STATUS.eq(ElevationStatus.PENDING.name))
            .and(ELEVATION.KIND.eq(ask.kind.name))
            .and(ELEVATION.SUBJECT_REF.eq(ask.subject))
            .and(ELEVATION.REQUESTER_REF.eq(ask.requester.value))
            .and(ELEVATION.CASE_ID.eq(ask.caseId))
            .fetchOne()
            ?.let { toRecord(it) }

    override fun insert(ask: ElevationAsk): ElevationRecord {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound: a change runs in a bound transaction" }
        val ref =
            ask.kind.refPrefix +
                ids
                    .next(clock.instant())
                    .toString()
                    .replace("-", "")
                    .takeLast(REF_DIGITS)
        dsl
            .insertInto(ELEVATION)
            .set(ELEVATION.ID, ids.next(clock.instant()))
            .set(ELEVATION.ELEVATION_REF, ref)
            .set(ELEVATION.KIND, ask.kind.name)
            .set(ELEVATION.SUBJECT_REF, ask.subject)
            .set(ELEVATION.REQUESTER_REF, ask.requester.value)
            .set(ELEVATION.CASE_ID, ask.caseId)
            .set(ELEVATION.STATUS, ElevationStatus.PENDING.name)
            .set(ELEVATION.CREATED_AT, now)
            .set(ELEVATION.CREATED_BY, actor)
            .set(ELEVATION.UPDATED_AT, now)
            .set(ELEVATION.UPDATED_BY, actor)
            .set(ELEVATION.VERSION, 0L)
            .execute()
        return checkNotNull(lock(ref)) { "the record just written is not readable" }
    }

    override fun lock(ref: String): ElevationRecord? =
        dsl
            .selectFrom(ELEVATION)
            .where(ELEVATION.ELEVATION_REF.eq(ref))
            .forUpdate()
            .fetchOne()
            ?.let { toRecord(it) }

    override fun update(
        before: ElevationRecord,
        after: ElevationRecord,
    ) {
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound: a change runs in a bound transaction" }
        val changed =
            dsl
                .update(ELEVATION)
                .set(ELEVATION.STATUS, after.status.name)
                .set(ELEVATION.VALID_FROM, after.window?.from?.atOffset(ZoneOffset.UTC))
                .set(ELEVATION.VALID_UNTIL, after.window?.until?.atOffset(ZoneOffset.UTC))
                .set(ELEVATION.DECIDED_BY, after.decidedBy?.value)
                .set(ELEVATION.UPDATED_AT, clock.instant().atOffset(ZoneOffset.UTC))
                .set(ELEVATION.UPDATED_BY, actor)
                .set(ELEVATION.VERSION, after.version)
                .where(ELEVATION.ELEVATION_REF.eq(before.ref))
                .and(ELEVATION.VERSION.eq(before.version))
                .execute()
        check(changed == 1) { "the record ${before.ref} changed since it was read" }
    }

    override fun findByRequester(requester: IdentityRef): List<ElevationRecord> =
        dsl
            .selectFrom(ELEVATION)
            .where(ELEVATION.REQUESTER_REF.eq(requester.value))
            .fetch()
            .map { toRecord(it) }

    private fun toRecord(row: ElevationRow): ElevationRecord {
        val from = row.validFrom
        val until = row.validUntil
        return ElevationRecord(
            ref = checkNotNull(row.elevationRef),
            tenant = TenantRef(checkNotNull(row.tenantId).toString()),
            ask =
                ElevationAsk(
                    ElevationKind.valueOf(checkNotNull(row.kind)),
                    checkNotNull(row.subjectRef),
                    IdentityRef(checkNotNull(row.requesterRef)),
                    checkNotNull(row.caseId),
                ),
            status = ElevationStatus.valueOf(checkNotNull(row.status)),
            window = if (from != null && until != null) ValidityWindow(from.toInstant(), until.toInstant()) else null,
            decidedBy = row.decidedBy?.let { IdentityRef(it) },
            version = checkNotNull(row.version),
        )
    }

    private companion object {
        const val REF_DIGITS = 12
    }
}
