package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.OffboardingExportJob.Companion.OFFBOARDING_EXPORT_JOB
import com.dynokore.gst.evidence.application.export.ExportJob
import com.dynokore.gst.evidence.application.export.ExportJobs
import com.dynokore.gst.evidence.application.export.ExportRequest
import com.dynokore.gst.evidence.application.export.ExportState
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Record
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * `offboarding_export_job` over the service's tenant-bound [DSLContext] (jOOQ only, STK §3; INV-REQ-877).
 * Only a REQUESTED row changes: a move of a READY or FAILED job changes no row and returns the stored job.
 */
class JooqOffboardingExportJobs(
    private val dsl: DSLContext,
    private val clock: Clock,
) : ExportJobs {
    private val t = OFFBOARDING_EXPORT_JOB

    override fun find(
        tenant: TenantId,
        offboardingRef: String,
    ): ExportJob? =
        dsl
            .selectFrom(t)
            .where(t.TENANT_ID.eq(tenant.value), t.OFFBOARDING_REF.eq(offboardingRef))
            .fetchOne()
            ?.let(::toJob)

    override fun register(request: ExportRequest): ExportJob {
        val jobId = UUID.randomUUID()
        dsl
            .insertInto(t)
            .set(t.TENANT_ID, request.tenant.value)
            .set(t.JOB_ID, jobId)
            .set(t.OFFBOARDING_REF, request.offboardingRef)
            .set(t.EXPORT_REF, request.exportRef)
            .set(t.SCOPE, request.scope.name)
            .set(t.ENTITY_REF, request.entityRef)
            .set(t.REQUESTED_EVENT_ID, request.requestedEventId)
            .set(t.STATE, ExportState.REQUESTED.name)
            .set(t.REQUESTED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .onConflict(t.TENANT_ID, t.OFFBOARDING_REF)
            .doNothing()
            .execute()
        return checkNotNull(find(request.tenant, request.offboardingRef)) { "job vanished after register" }
    }

    override fun markReady(
        job: ExportJob,
        manifestRef: String,
        itemCount: Int,
        contents: List<String>,
    ): ExportJob {
        dsl
            .update(t)
            .set(t.STATE, ExportState.READY.name)
            .set(t.FINISHED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .set(t.MANIFEST_URI, manifestRef)
            .set(t.ITEM_COUNT, itemCount.toLong())
            .set(t.CONTENTS, contents.toTypedArray())
            .where(requested(job))
            .execute()
        return stored(job)
    }

    override fun markFailed(
        job: ExportJob,
        reason: String,
    ): ExportJob {
        dsl
            .update(t)
            .set(t.STATE, ExportState.FAILED.name)
            .set(t.FINISHED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .set(t.FAILURE_REASON, reason)
            .where(requested(job))
            .execute()
        return stored(job)
    }

    private fun requested(job: ExportJob) =
        t.TENANT_ID
            .eq(job.tenant.value)
            .and(t.OFFBOARDING_REF.eq(job.offboardingRef))
            .and(t.STATE.eq(ExportState.REQUESTED.name))

    private fun stored(job: ExportJob): ExportJob =
        checkNotNull(find(job.tenant, job.offboardingRef)) { "no export job for ${job.offboardingRef}" }

    private fun toJob(r: Record): ExportJob =
        ExportJob(
            tenant = TenantId(checkNotNull(r.get(t.TENANT_ID))),
            offboardingRef = checkNotNull(r.get(t.OFFBOARDING_REF)),
            exportRef = checkNotNull(r.get(t.EXPORT_REF)),
            state = ExportState.valueOf(checkNotNull(r.get(t.STATE))),
            manifestRef = r.get(t.MANIFEST_URI),
            itemCount = r.get(t.ITEM_COUNT)?.toInt(),
            contents = r.get(t.CONTENTS)?.filterNotNull().orEmpty(),
            failureReason = r.get(t.FAILURE_REASON),
        )
}
