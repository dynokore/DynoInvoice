package com.dynokore.gst.reconciliation.adapters.persistence

import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.GST_RETURN
import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.RECONCILIATION_RUN
import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.RECONCILIATION_RUN_BOX
import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.RECONCILIATION_RUN_NOT_RECONCILABLE
import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.RECONCILIATION_RUN_UNMAPPED_LINE
import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.RECONCILIATION_RUN_VARIANCE
import com.dynokore.gst.reconciliation.application.run.CompletedRuns
import com.dynokore.gst.reconciliation.application.run.RecordedRun
import com.dynokore.gst.reconciliation.domain.run.RunOutcome
import org.jooq.DSLContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The append-only run store (INV-T-24376, INV-REQ-656, INV-AC-24375): one `reconciliation_run` row per execution of
 * the run job and its Box, variance, unmapped-line and not-reconcilable rows; an earlier run is never touched. The
 * caller binds the tenant and opens the transaction, so a rollback leaves no run row. Every statement names the tenant
 * in its WHERE or VALUES as well as the bound tenant of the row-level security. Money keeps scale 2.
 */
class JooqCompletedRuns(
    private val dsl: DSLContext,
    private val newRunId: () -> UUID = UUID::randomUUID,
) : CompletedRuns {
    override fun record(
        tenant: String,
        entityRef: String,
        periodRef: String,
        outcome: RunOutcome.Completed,
        completedAt: Instant,
    ): RecordedRun {
        val tenantId = UUID.fromString(tenant)
        val form =
            dsl
                .select(GST_RETURN.FORM)
                .from(GST_RETURN)
                .where(GST_RETURN.TENANT_ID.eq(tenantId))
                .and(GST_RETURN.ENTITY_REF.eq(entityRef))
                .and(GST_RETURN.PERIOD_REF.eq(periodRef))
                .and(GST_RETURN.VERSION.eq(outcome.returnVersion))
                .fetchOne(GST_RETURN.FORM)
                ?: throw IllegalStateException(
                    "no stored return for entity $entityRef, period $periodRef, version ${outcome.returnVersion}",
                )
        val runId = newRunId()
        val stored = completedAt.truncatedTo(ChronoUnit.MICROS)
        val residual = outcome.result.lines.fold(BigDecimal.ZERO) { acc, line -> acc.add(line.unexplainedResidual) }
        dsl
            .insertInto(RECONCILIATION_RUN)
            .set(RECONCILIATION_RUN.TENANT_ID, tenantId)
            .set(RECONCILIATION_RUN.RUN_ID, runId)
            .set(RECONCILIATION_RUN.ENTITY_REF, entityRef)
            .set(RECONCILIATION_RUN.PERIOD_REF, periodRef)
            .set(RECONCILIATION_RUN.RETURN_VERSION, outcome.returnVersion)
            .set(RECONCILIATION_RUN.RETURN_FORM, form)
            .set(RECONCILIATION_RUN.COMPLETED_AT, stored.atOffset(ZoneOffset.UTC))
            .set(RECONCILIATION_RUN.UNEXPLAINED_RESIDUAL, money(residual))
            .set(RECONCILIATION_RUN.RAISES_PER003, outcome.raisesPer003)
            .execute()
        outcome.result.lines.forEach { line ->
            val box = line.comparison.key
            dsl
                .insertInto(RECONCILIATION_RUN_BOX)
                .set(RECONCILIATION_RUN_BOX.TENANT_ID, tenantId)
                .set(RECONCILIATION_RUN_BOX.RUN_ID, runId)
                .set(RECONCILIATION_RUN_BOX.BOX, box)
                .set(RECONCILIATION_RUN_BOX.SUBMITTED_AMOUNT, money(line.comparison.submittedAmount))
                .set(RECONCILIATION_RUN_BOX.FILED_AMOUNT, money(line.comparison.returnAmount))
                .set(RECONCILIATION_RUN_BOX.VARIANCE, money(line.comparison.variance))
                .set(RECONCILIATION_RUN_BOX.VARIANCE_CLASS, line.classified.singleOrNull()?.category?.code)
                .set(RECONCILIATION_RUN_BOX.UNEXPLAINED_RESIDUAL, money(line.unexplainedResidual))
                .execute()
            line.classified.forEach { classified ->
                dsl
                    .insertInto(RECONCILIATION_RUN_VARIANCE)
                    .set(RECONCILIATION_RUN_VARIANCE.TENANT_ID, tenantId)
                    .set(RECONCILIATION_RUN_VARIANCE.RUN_ID, runId)
                    .set(RECONCILIATION_RUN_VARIANCE.BOX, box)
                    .set(RECONCILIATION_RUN_VARIANCE.CATEGORY_CODE, classified.category.code)
                    .set(RECONCILIATION_RUN_VARIANCE.AMOUNT, money(classified.amount))
                    .set(RECONCILIATION_RUN_VARIANCE.LINKED_RECORDS, classified.linkedRecords.toTypedArray())
                    .execute()
            }
        }
        outcome.unmappedLines.forEach { line ->
            dsl
                .insertInto(RECONCILIATION_RUN_UNMAPPED_LINE)
                .set(RECONCILIATION_RUN_UNMAPPED_LINE.TENANT_ID, tenantId)
                .set(RECONCILIATION_RUN_UNMAPPED_LINE.RUN_ID, runId)
                .set(RECONCILIATION_RUN_UNMAPPED_LINE.DOC_UUID, line.docUuid)
                .set(RECONCILIATION_RUN_UNMAPPED_LINE.VERSION, line.version)
                .set(RECONCILIATION_RUN_UNMAPPED_LINE.LINE_NUMBER, line.lineNumber)
                .set(RECONCILIATION_RUN_UNMAPPED_LINE.TAX_CODE, line.taxCode)
                .execute()
        }
        outcome.notReconcilable.forEach { document ->
            dsl
                .insertInto(RECONCILIATION_RUN_NOT_RECONCILABLE)
                .set(RECONCILIATION_RUN_NOT_RECONCILABLE.TENANT_ID, tenantId)
                .set(RECONCILIATION_RUN_NOT_RECONCILABLE.RUN_ID, runId)
                .set(RECONCILIATION_RUN_NOT_RECONCILABLE.DOC_UUID, document.docUuid)
                .set(RECONCILIATION_RUN_NOT_RECONCILABLE.VERSION, document.version)
                .set(RECONCILIATION_RUN_NOT_RECONCILABLE.REASON, document.reason.name)
                .execute()
        }
        return RecordedRun(runId.toString(), entityRef, periodRef, outcome.returnVersion, stored)
    }

    private fun money(value: BigDecimal): BigDecimal = value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY)

    private companion object {
        const val MONEY_SCALE = 2
    }
}
