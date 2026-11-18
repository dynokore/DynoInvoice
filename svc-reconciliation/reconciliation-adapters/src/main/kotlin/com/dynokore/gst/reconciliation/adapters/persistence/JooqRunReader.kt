package com.dynokore.gst.reconciliation.adapters.persistence

import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.RECONCILIATION_RUN
import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.RECONCILIATION_RUN_BOX
import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.RECONCILIATION_RUN_UNMAPPED_LINE
import com.dynokore.gst.reconciliation.application.run.RecordedRun
import com.dynokore.gst.reconciliation.application.run.RunBox
import com.dynokore.gst.reconciliation.application.run.RunCursor
import com.dynokore.gst.reconciliation.application.run.RunReader
import com.dynokore.gst.reconciliation.application.run.RunSnapshot
import com.dynokore.gst.reconciliation.application.run.RunUnmappedLine
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.ZoneOffset
import java.util.UUID

/**
 * Reads the append-only run store (INV-T-24376, INV-AC-24375). Pages are ordered by `completed_at` then `run_id`,
 * both descending, and continue strictly after the cursor row. Every statement names the tenant in its WHERE as well
 * as the bound tenant of the row-level security. A Box's `varianceClass` is the stored single classified category
 * code; it is absent when the Box has none (never `UNEXPLAINED`).
 */
class JooqRunReader(
    private val dsl: DSLContext,
) : RunReader {
    override fun latest(
        tenant: String,
        entityRef: String,
        periodRef: String,
    ): RunSnapshot? =
        dsl
            .selectFrom(RECONCILIATION_RUN)
            .where(RECONCILIATION_RUN.TENANT_ID.eq(UUID.fromString(tenant)))
            .and(RECONCILIATION_RUN.ENTITY_REF.eq(entityRef))
            .and(RECONCILIATION_RUN.PERIOD_REF.eq(periodRef))
            .orderBy(RECONCILIATION_RUN.COMPLETED_AT.desc(), RECONCILIATION_RUN.RUN_ID.desc())
            .limit(1)
            .fetchOne()
            ?.let { snapshot(UUID.fromString(tenant), it) }

    override fun page(
        tenant: String,
        entityRef: String,
        periodRef: String,
        limit: Int,
        after: RunCursor?,
    ): List<RecordedRun> {
        require(limit >= 1) { "limit must be at least 1" }
        var condition =
            RECONCILIATION_RUN.TENANT_ID
                .eq(UUID.fromString(tenant))
                .and(RECONCILIATION_RUN.ENTITY_REF.eq(entityRef))
                .and(RECONCILIATION_RUN.PERIOD_REF.eq(periodRef))
        if (after != null) {
            condition =
                condition.and(
                    DSL
                        .row(RECONCILIATION_RUN.COMPLETED_AT, RECONCILIATION_RUN.RUN_ID)
                        .lt(
                            DSL.row(
                                after.completedAt.atOffset(ZoneOffset.UTC),
                                UUID.fromString(after.runId),
                            ),
                        ),
                )
        }
        return dsl
            .selectFrom(RECONCILIATION_RUN)
            .where(condition)
            .orderBy(RECONCILIATION_RUN.COMPLETED_AT.desc(), RECONCILIATION_RUN.RUN_ID.desc())
            .limit(limit)
            .fetch()
            .map(::recorded)
    }

    override fun find(
        tenant: String,
        runId: String,
    ): RunSnapshot? {
        val tenantId = UUID.fromString(tenant)
        return dsl
            .selectFrom(RECONCILIATION_RUN)
            .where(RECONCILIATION_RUN.TENANT_ID.eq(tenantId))
            .and(RECONCILIATION_RUN.RUN_ID.eq(UUID.fromString(runId)))
            .fetchOne()
            ?.let { snapshot(tenantId, it) }
    }

    private fun recorded(row: Record): RecordedRun =
        RecordedRun(
            runId = checkNotNull(row.get(RECONCILIATION_RUN.RUN_ID)).toString(),
            entityRef = checkNotNull(row.get(RECONCILIATION_RUN.ENTITY_REF)),
            periodRef = checkNotNull(row.get(RECONCILIATION_RUN.PERIOD_REF)),
            returnVersion = checkNotNull(row.get(RECONCILIATION_RUN.RETURN_VERSION)),
            completedAt = checkNotNull(row.get(RECONCILIATION_RUN.COMPLETED_AT)).toInstant(),
        )

    private fun snapshot(
        tenantId: UUID,
        row: Record,
    ): RunSnapshot {
        val runId = checkNotNull(row.get(RECONCILIATION_RUN.RUN_ID))
        return RunSnapshot(
            run = recorded(row),
            returnForm = checkNotNull(row.get(RECONCILIATION_RUN.RETURN_FORM)),
            boxes = boxes(tenantId, runId),
            unexplainedResidual = checkNotNull(row.get(RECONCILIATION_RUN.UNEXPLAINED_RESIDUAL)),
            unmappedLines = unmappedLines(tenantId, runId),
            raisesPer003 = checkNotNull(row.get(RECONCILIATION_RUN.RAISES_PER003)),
        )
    }

    private fun boxes(
        tenantId: UUID,
        runId: UUID,
    ): List<RunBox> =
        dsl
            .selectFrom(RECONCILIATION_RUN_BOX)
            .where(RECONCILIATION_RUN_BOX.TENANT_ID.eq(tenantId))
            .and(RECONCILIATION_RUN_BOX.RUN_ID.eq(runId))
            .orderBy(RECONCILIATION_RUN_BOX.BOX.asc())
            .fetch()
            .map {
                RunBox(
                    box = checkNotNull(it.box),
                    submittedAmount = checkNotNull(it.submittedAmount),
                    filedAmount = checkNotNull(it.filedAmount),
                    variance = checkNotNull(it.variance),
                    varianceClass = it.varianceClass,
                    unexplainedResidual = checkNotNull(it.unexplainedResidual),
                )
            }

    private fun unmappedLines(
        tenantId: UUID,
        runId: UUID,
    ): List<RunUnmappedLine> =
        dsl
            .selectFrom(RECONCILIATION_RUN_UNMAPPED_LINE)
            .where(RECONCILIATION_RUN_UNMAPPED_LINE.TENANT_ID.eq(tenantId))
            .and(RECONCILIATION_RUN_UNMAPPED_LINE.RUN_ID.eq(runId))
            .orderBy(
                RECONCILIATION_RUN_UNMAPPED_LINE.DOC_UUID.asc(),
                RECONCILIATION_RUN_UNMAPPED_LINE.VERSION.asc(),
                RECONCILIATION_RUN_UNMAPPED_LINE.LINE_NUMBER.asc(),
            ).fetch()
            .map {
                RunUnmappedLine(
                    docUuid = checkNotNull(it.docUuid),
                    version = checkNotNull(it.version),
                    lineNumber = checkNotNull(it.lineNumber),
                    taxCode = checkNotNull(it.taxCode),
                )
            }
}
