package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.AuditPackJob.Companion.AUDIT_PACK_JOB
import com.dynokore.gst.evidence.application.export.AuditPackExportJob
import com.dynokore.gst.evidence.application.export.AuditPackJobs
import com.dynokore.gst.evidence.application.export.AuditPackState
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Record
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * `audit_pack_job` over the service's tenant-bound [DSLContext] (jOOQ only, STK §3; INV-REQ-988).
 * Only a PENDING row changes: a move of a READY or FAILED job changes no row and returns the stored job.
 */
class JooqAuditPackJobs(
    private val dsl: DSLContext,
    private val clock: Clock,
) : AuditPackJobs {
    private val t = AUDIT_PACK_JOB

    override fun register(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
        requestedBy: String,
    ): AuditPackExportJob {
        dsl
            .insertInto(t)
            .set(t.TENANT_ID, tenant.value)
            .set(t.PACK_ID, UUID.randomUUID())
            .set(t.ENTITY_REF, entityRef)
            .set(t.PERIOD_REF, periodRef)
            .set(t.STATE, AuditPackState.PENDING.name)
            .set(t.REQUESTED_AT, now())
            .set(t.REQUESTED_BY, requestedBy)
            .onConflict(t.TENANT_ID, t.ENTITY_REF, t.PERIOD_REF)
            .doNothing()
            .execute()
        return checkNotNull(
            dsl
                .selectFrom(t)
                .where(t.TENANT_ID.eq(tenant.value), t.ENTITY_REF.eq(entityRef), t.PERIOD_REF.eq(periodRef))
                .fetchOne()
                ?.let(::toJob),
        ) { "audit-pack job vanished after register" }
    }

    override fun find(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
        packId: UUID,
    ): AuditPackExportJob? =
        dsl
            .selectFrom(t)
            .where(
                t.TENANT_ID.eq(tenant.value),
                t.ENTITY_REF.eq(entityRef),
                t.PERIOD_REF.eq(periodRef),
                t.PACK_ID.eq(packId),
            ).fetchOne()
            ?.let(::toJob)

    override fun markReady(
        job: AuditPackExportJob,
        downloadUri: String,
        contents: List<String>,
    ): AuditPackExportJob {
        dsl
            .update(t)
            .set(t.STATE, AuditPackState.READY.name)
            .set(t.READY_AT, now())
            .set(t.DOWNLOAD_URI, downloadUri)
            .set(t.CONTENTS, contents.toTypedArray())
            .where(pending(job))
            .execute()
        return stored(job)
    }

    override fun markFailed(
        job: AuditPackExportJob,
        reason: String,
    ): AuditPackExportJob {
        dsl
            .update(t)
            .set(t.STATE, AuditPackState.FAILED.name)
            .set(t.FAILURE_REASON, reason)
            .where(pending(job))
            .execute()
        return stored(job)
    }

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private fun pending(job: AuditPackExportJob) =
        t.TENANT_ID
            .eq(job.tenant.value)
            .and(t.PACK_ID.eq(job.packId))
            .and(t.STATE.eq(AuditPackState.PENDING.name))

    private fun stored(job: AuditPackExportJob): AuditPackExportJob =
        checkNotNull(find(job.tenant, job.entityRef, job.periodRef, job.packId)) { "no audit-pack job ${job.packId}" }

    private fun toJob(r: Record): AuditPackExportJob =
        AuditPackExportJob(
            tenant = TenantId(checkNotNull(r.get(t.TENANT_ID))),
            packId = checkNotNull(r.get(t.PACK_ID)),
            entityRef = checkNotNull(r.get(t.ENTITY_REF)),
            periodRef = checkNotNull(r.get(t.PERIOD_REF)),
            state = AuditPackState.valueOf(checkNotNull(r.get(t.STATE))),
            requestedAt = checkNotNull(r.get(t.REQUESTED_AT)).toInstant(),
            requestedBy = checkNotNull(r.get(t.REQUESTED_BY)),
            readyAt = r.get(t.READY_AT)?.toInstant(),
            downloadUri = r.get(t.DOWNLOAD_URI),
            contents = r.get(t.CONTENTS)?.filterNotNull().orEmpty(),
            failureReason = r.get(t.FAILURE_REASON),
        )
}
