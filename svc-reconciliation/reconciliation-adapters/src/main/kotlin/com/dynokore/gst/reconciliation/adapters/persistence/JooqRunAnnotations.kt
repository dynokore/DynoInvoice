package com.dynokore.gst.reconciliation.adapters.persistence

import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.records.WorkPaperAnnotationRecord
import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.WORK_PAPER_ANNOTATION
import com.dynokore.gst.reconciliation.application.workpaper.RunAnnotations
import com.dynokore.gst.reconciliation.application.workpaper.StoredAnnotation
import com.dynokore.gst.reconciliation.domain.annotation.Annotation
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.ZoneOffset
import java.util.UUID

/**
 * The append-only annotation store (INV-T-24380, INV-AC-24379, INV-REQ-633 b4, INV-REQ-1010). An annotation is a new
 * revision of (tenant, run, box); a stored row is never updated or deleted (NIST SP 800-53 AU-9). The caller binds the
 * tenant and opens the transaction; every statement names the tenant in its WHERE or values as well as the bound
 * tenant of the row-level security. The acting user ID is stored, never a role (INV-Q-10647).
 */
class JooqRunAnnotations(
    private val dsl: DSLContext,
) : RunAnnotations {
    override fun latest(
        tenant: String,
        runId: String,
        box: String,
    ): StoredAnnotation? =
        dsl
            .selectFrom(WORK_PAPER_ANNOTATION)
            .where(WORK_PAPER_ANNOTATION.TENANT_ID.eq(UUID.fromString(tenant)))
            .and(WORK_PAPER_ANNOTATION.RUN_ID.eq(runId))
            .and(WORK_PAPER_ANNOTATION.BOX.eq(box))
            .orderBy(WORK_PAPER_ANNOTATION.REVISION.desc())
            .limit(1)
            .fetchOne()
            ?.let(::stored)

    override fun all(
        tenant: String,
        runId: String,
    ): List<StoredAnnotation> =
        dsl
            .selectFrom(WORK_PAPER_ANNOTATION)
            .where(WORK_PAPER_ANNOTATION.TENANT_ID.eq(UUID.fromString(tenant)))
            .and(WORK_PAPER_ANNOTATION.RUN_ID.eq(runId))
            .orderBy(WORK_PAPER_ANNOTATION.BOX.asc(), WORK_PAPER_ANNOTATION.REVISION.desc())
            .fetch()
            .distinctBy { it.box }
            .map(::stored)

    /**
     * Revision = latest revision + 1. A primary-key conflict (a concurrent writer took the revision) stores nothing
     * and answers false; `on conflict do nothing` keeps the surrounding transaction usable and never overwrites.
     */
    override fun insert(
        tenant: String,
        stored: StoredAnnotation,
    ): Boolean {
        val tenantId = UUID.fromString(tenant)
        val next =
            (
                dsl
                    .select(DSL.max(WORK_PAPER_ANNOTATION.REVISION))
                    .from(WORK_PAPER_ANNOTATION)
                    .where(WORK_PAPER_ANNOTATION.TENANT_ID.eq(tenantId))
                    .and(WORK_PAPER_ANNOTATION.RUN_ID.eq(stored.runId))
                    .and(WORK_PAPER_ANNOTATION.BOX.eq(stored.annotation.box))
                    .fetchOne(0, Int::class.java) ?: 0
            ) + 1
        return dsl
            .insertInto(WORK_PAPER_ANNOTATION)
            .set(WORK_PAPER_ANNOTATION.TENANT_ID, tenantId)
            .set(WORK_PAPER_ANNOTATION.RUN_ID, stored.runId)
            .set(WORK_PAPER_ANNOTATION.BOX, stored.annotation.box)
            .set(WORK_PAPER_ANNOTATION.REVISION, next)
            .set(WORK_PAPER_ANNOTATION.VARIANCE_CLASS, stored.annotation.varianceClass?.value)
            .set(WORK_PAPER_ANNOTATION.REFERENCES, stored.annotation.references.toTypedArray())
            .set(WORK_PAPER_ANNOTATION.ANNOTATED_BY, stored.annotatedBy)
            .set(WORK_PAPER_ANNOTATION.ANNOTATED_AT, stored.annotatedAt.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() == 1
    }

    private fun stored(row: WorkPaperAnnotationRecord): StoredAnnotation =
        StoredAnnotation(
            runId = checkNotNull(row.runId),
            annotation =
                Annotation(
                    box = checkNotNull(row.box),
                    varianceClass = row.varianceClass?.let { VarianceClass.valueOf(it) },
                    references = row.references.orEmpty().filterNotNull(),
                ),
            annotatedBy = checkNotNull(row.annotatedBy),
            annotatedAt = checkNotNull(row.annotatedAt).toInstant(),
        )
}
