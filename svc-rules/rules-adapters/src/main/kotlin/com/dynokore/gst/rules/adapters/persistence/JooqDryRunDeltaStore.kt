package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.DRY_RUN_DELTA
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.DRY_RUN_DELTA_DOCUMENT
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.DRY_RUN_DELTA_TOTAL
import com.dynokore.gst.rules.application.DryRunDeltaStore
import com.dynokore.gst.rules.application.DryRunStart
import com.dynokore.gst.rules.application.DryRunState
import com.dynokore.gst.rules.application.StoredDryRun
import com.dynokore.gst.rules.domain.dryrun.ChangeType
import com.dynokore.gst.rules.domain.dryrun.ChangeTypeTotal
import com.dynokore.gst.rules.domain.dryrun.ChangedDocument
import com.dynokore.gst.rules.domain.dryrun.DeltaListing
import com.dynokore.gst.rules.domain.dryrun.DocumentOutcome
import com.dynokore.gst.rules.domain.dryrun.DryRunDelta
import com.dynokore.gst.rules.domain.dryrun.PeriodDelta
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The stored dry-run delta over `rules.dry_run_delta`, `rules.dry_run_delta_document` and `rules.dry_run_delta_total`
 * (INV-REQ-772, INV-T-19902), jOOQ only. The tenant comes from the signed binding and row-level security hides every
 * other tenant's rows. `complete` writes the document rows, the total rows and the update to COMPLETE in one
 * transaction, so that all of them exist or none does; the trigger of the run table refuses any later change.
 */
class JooqDryRunDeltaStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : DryRunDeltaStore {
    override fun begin(start: DryRunStart) {
        dsl
            .insertInto(DRY_RUN_DELTA)
            .set(DRY_RUN_DELTA.ID, UUID.fromString(start.dryRunId))
            .set(DRY_RUN_DELTA.TENANT_ID, tenants.current().value)
            .set(DRY_RUN_DELTA.PACK_TYPE, start.packType)
            .set(DRY_RUN_DELTA.PACK_VERSION, start.candidateVersion)
            .set(DRY_RUN_DELTA.STATE, DryRunState.RUNNING.name)
            .set(DRY_RUN_DELTA.STARTED_BY, start.startedBy)
            .set(DRY_RUN_DELTA.STARTED_AT, start.startedAt.atOffset(ZoneOffset.UTC))
            .execute()
    }

    override fun complete(
        dryRunId: String,
        completedAt: Instant,
        delta: DryRunDelta,
    ) {
        val id = UUID.fromString(dryRunId)
        val tenant = tenants.current().value
        dsl.transaction { configuration ->
            val writer = DeltaWriter(DSL.using(configuration), tenant, id)
            val straddling = delta.straddlingCreditNotes.map { it.docUuid }.toSet()
            delta.documents.forEachIndexed { index, doc ->
                writer.document(index + 1, doc, doc.docUuid in straddling)
            }
            delta.changeTypeTotals.forEachIndexed { index, t ->
                val sums = TotalRow(t.documentCount, t.valuesAbsentCount, t.taxableValue, t.gstValue)
                writer.total(TotalKey(DeltaRows.CHANGE_TYPE, t.changeType.name, index + 1), sums)
            }
            delta.byPeriod.forEachIndexed { index, p ->
                val sums = TotalRow(p.documentCount, p.valuesAbsentCount, p.taxableValue, p.gstValue)
                writer.total(TotalKey(DeltaRows.PERIOD, p.periodRef, index + 1), sums)
            }
            writer.markComplete(completedAt, delta.documents.size)
        }
    }

    override fun unavailable(
        dryRunId: String,
        reason: String,
    ) {
        val updated =
            dsl
                .update(DRY_RUN_DELTA)
                .set(DRY_RUN_DELTA.STATE, DryRunState.SAMPLE_UNAVAILABLE.name)
                .set(DRY_RUN_DELTA.UNAVAILABLE_REASON, reason)
                .where(DRY_RUN_DELTA.ID.eq(UUID.fromString(dryRunId)))
                .and(DRY_RUN_DELTA.TENANT_ID.eq(tenants.current().value))
                .and(DRY_RUN_DELTA.STATE.eq(DryRunState.RUNNING.name))
                .execute()
        check(updated == 1) { "dry run $dryRunId is not a RUNNING run of this tenant" }
    }

    override fun find(dryRunId: String): StoredDryRun? {
        val id = runCatching { UUID.fromString(dryRunId) }.getOrNull()
        val run =
            id?.let {
                dsl
                    .selectFrom(DRY_RUN_DELTA)
                    .where(DRY_RUN_DELTA.ID.eq(it))
                    .and(DRY_RUN_DELTA.TENANT_ID.eq(tenants.current().value))
                    .fetchOne()
            }
        return run?.let {
            val state = DryRunState.valueOf(DeltaRows.req(it, DRY_RUN_DELTA.STATE))
            StoredDryRun(
                dryRunId = DeltaRows.req(it, DRY_RUN_DELTA.ID).toString(),
                startedBy = DeltaRows.req(it, DRY_RUN_DELTA.STARTED_BY),
                state = state,
                reason = it.unavailableReason,
                delta = if (state == DryRunState.COMPLETE) rebuild(DeltaRows.req(it, DRY_RUN_DELTA.ID)) else null,
            )
        }
    }

    private fun rebuild(id: UUID): DryRunDelta {
        val rows =
            dsl
                .selectFrom(DRY_RUN_DELTA_DOCUMENT)
                .where(DRY_RUN_DELTA_DOCUMENT.DELTA_ID.eq(id))
                .orderBy(DRY_RUN_DELTA_DOCUMENT.POSITION)
                .fetch()
        val documents = rows.map(DeltaRows::document)
        val straddling =
            rows
                .filter { DeltaRows.req(it, DRY_RUN_DELTA_DOCUMENT.STRADDLING_CREDIT_NOTE) }
                .map { DeltaRows.req(it, DRY_RUN_DELTA_DOCUMENT.DOC_UUID).toString() }
                .toSet()
        val totals =
            dsl
                .selectFrom(DRY_RUN_DELTA_TOTAL)
                .where(DRY_RUN_DELTA_TOTAL.DELTA_ID.eq(id))
                .orderBy(DRY_RUN_DELTA_TOTAL.KIND, DRY_RUN_DELTA_TOTAL.POSITION)
                .fetch()
        return DryRunDelta(
            changedCount = documents.size,
            documents = documents,
            changeTypeTotals = totals.filter { it.kind == DeltaRows.CHANGE_TYPE }.map(DeltaRows::changeTypeTotal),
            byPeriod = totals.filter { it.kind == DeltaRows.PERIOD }.map(DeltaRows::period),
            straddlingCreditNotes = documents.filter { it.docUuid in straddling }.map(DeltaRows::listing),
            sample = documents.filter { it.docUuid !in straddling }.map(DeltaRows::listing),
        )
    }
}

private data class TotalKey(
    val kind: String,
    val key: String,
    val position: Int,
)

private data class TotalRow(
    val documentCount: Int,
    val valuesAbsentCount: Int,
    val taxableValue: String,
    val gstValue: String,
)

/** The writes of one `complete`, all on the transaction it opens. */
private class DeltaWriter(
    private val tx: DSLContext,
    private val tenant: UUID,
    private val id: UUID,
) {
    fun document(
        position: Int,
        doc: ChangedDocument,
        straddling: Boolean,
    ) {
        tx
            .insertInto(DRY_RUN_DELTA_DOCUMENT)
            .set(DRY_RUN_DELTA_DOCUMENT.TENANT_ID, tenant)
            .set(DRY_RUN_DELTA_DOCUMENT.DELTA_ID, id)
            .set(DRY_RUN_DELTA_DOCUMENT.POSITION, position)
            .set(DRY_RUN_DELTA_DOCUMENT.DOC_UUID, UUID.fromString(doc.docUuid))
            .set(DRY_RUN_DELTA_DOCUMENT.PERIOD_REF, doc.periodRef)
            .set(DRY_RUN_DELTA_DOCUMENT.TRANSACTION_DATE, doc.transactionDate)
            .set(DRY_RUN_DELTA_DOCUMENT.PRECEDING_DOCUMENT_DATE, doc.precedingDocumentDate)
            .set(DRY_RUN_DELTA_DOCUMENT.DOCUMENT_FUNCTION, doc.documentFunction)
            .set(DRY_RUN_DELTA_DOCUMENT.SUPPLY_TYPE, doc.supplyType)
            .set(DRY_RUN_DELTA_DOCUMENT.CHANGE_TYPE, doc.changeType.name)
            .set(DRY_RUN_DELTA_DOCUMENT.OLD_CODE, doc.oldOutcome.code)
            .set(DRY_RUN_DELTA_DOCUMENT.OLD_SEVERITY, doc.oldOutcome.severity)
            .set(DRY_RUN_DELTA_DOCUMENT.NEW_CODE, doc.newOutcome.code)
            .set(DRY_RUN_DELTA_DOCUMENT.NEW_SEVERITY, doc.newOutcome.severity)
            .set(DRY_RUN_DELTA_DOCUMENT.TAXABLE_VALUE, BigDecimal(doc.taxableValue))
            .set(DRY_RUN_DELTA_DOCUMENT.GST_VALUE, BigDecimal(doc.gstValue))
            .set(DRY_RUN_DELTA_DOCUMENT.VALUES_ABSENT, doc.sgdValuesAbsent)
            .set(DRY_RUN_DELTA_DOCUMENT.STRADDLING_CREDIT_NOTE, straddling)
            .execute()
    }

    fun total(
        key: TotalKey,
        sums: TotalRow,
    ) {
        tx
            .insertInto(DRY_RUN_DELTA_TOTAL)
            .set(DRY_RUN_DELTA_TOTAL.TENANT_ID, tenant)
            .set(DRY_RUN_DELTA_TOTAL.DELTA_ID, id)
            .set(DRY_RUN_DELTA_TOTAL.KIND, key.kind)
            .set(DRY_RUN_DELTA_TOTAL.KEY, key.key)
            .set(DRY_RUN_DELTA_TOTAL.POSITION, key.position)
            .set(DRY_RUN_DELTA_TOTAL.DOCUMENT_COUNT, sums.documentCount)
            .set(DRY_RUN_DELTA_TOTAL.VALUES_ABSENT_COUNT, sums.valuesAbsentCount)
            .set(DRY_RUN_DELTA_TOTAL.TAXABLE_VALUE, BigDecimal(sums.taxableValue))
            .set(DRY_RUN_DELTA_TOTAL.GST_VALUE, BigDecimal(sums.gstValue))
            .execute()
    }

    fun markComplete(
        completedAt: Instant,
        changedCount: Int,
    ) {
        val updated =
            tx
                .update(DRY_RUN_DELTA)
                .set(DRY_RUN_DELTA.STATE, DryRunState.COMPLETE.name)
                .set(DRY_RUN_DELTA.COMPLETED_AT, completedAt.atOffset(ZoneOffset.UTC))
                .set(DRY_RUN_DELTA.CHANGED_COUNT, changedCount)
                .where(DRY_RUN_DELTA.ID.eq(id))
                .and(DRY_RUN_DELTA.TENANT_ID.eq(tenant))
                .and(DRY_RUN_DELTA.STATE.eq(DryRunState.RUNNING.name))
                .execute()
        check(updated == 1) { "dry run $id is not a RUNNING run of this tenant" }
    }
}

/** The rebuilding of the delta from its rows. */
private object DeltaRows {
    const val CHANGE_TYPE = "CHANGE_TYPE"
    const val PERIOD = "PERIOD"
    private const val ZERO_MONEY = "0.00"

    fun <T : Any> req(
        row: Record,
        field: Field<T?>,
    ): T = checkNotNull(row.get(field)) { "${field.name} is null" }

    fun document(r: Record) =
        ChangedDocument(
            docUuid = req(r, DRY_RUN_DELTA_DOCUMENT.DOC_UUID).toString(),
            periodRef = req(r, DRY_RUN_DELTA_DOCUMENT.PERIOD_REF),
            transactionDate = req(r, DRY_RUN_DELTA_DOCUMENT.TRANSACTION_DATE),
            precedingDocumentDate = r.get(DRY_RUN_DELTA_DOCUMENT.PRECEDING_DOCUMENT_DATE),
            documentFunction = req(r, DRY_RUN_DELTA_DOCUMENT.DOCUMENT_FUNCTION),
            supplyType = r.get(DRY_RUN_DELTA_DOCUMENT.SUPPLY_TYPE),
            oldOutcome =
                DocumentOutcome(req(r, DRY_RUN_DELTA_DOCUMENT.OLD_CODE), r.get(DRY_RUN_DELTA_DOCUMENT.OLD_SEVERITY)),
            newOutcome =
                DocumentOutcome(req(r, DRY_RUN_DELTA_DOCUMENT.NEW_CODE), r.get(DRY_RUN_DELTA_DOCUMENT.NEW_SEVERITY)),
            changeType = ChangeType.valueOf(req(r, DRY_RUN_DELTA_DOCUMENT.CHANGE_TYPE)),
            taxableValue = req(r, DRY_RUN_DELTA_DOCUMENT.TAXABLE_VALUE).toPlainString(),
            gstValue = req(r, DRY_RUN_DELTA_DOCUMENT.GST_VALUE).toPlainString(),
            sgdValuesAbsent = req(r, DRY_RUN_DELTA_DOCUMENT.VALUES_ABSENT),
        )

    fun changeTypeTotal(r: Record) =
        ChangeTypeTotal(
            ChangeType.valueOf(req(r, DRY_RUN_DELTA_TOTAL.KEY)),
            req(r, DRY_RUN_DELTA_TOTAL.DOCUMENT_COUNT),
            req(r, DRY_RUN_DELTA_TOTAL.TAXABLE_VALUE).toPlainString(),
            req(r, DRY_RUN_DELTA_TOTAL.GST_VALUE).toPlainString(),
            req(r, DRY_RUN_DELTA_TOTAL.VALUES_ABSENT_COUNT),
        )

    fun period(r: Record) =
        PeriodDelta(
            req(r, DRY_RUN_DELTA_TOTAL.KEY),
            req(r, DRY_RUN_DELTA_TOTAL.DOCUMENT_COUNT),
            req(r, DRY_RUN_DELTA_TOTAL.TAXABLE_VALUE).toPlainString(),
            req(r, DRY_RUN_DELTA_TOTAL.GST_VALUE).toPlainString(),
            req(r, DRY_RUN_DELTA_TOTAL.VALUES_ABSENT_COUNT),
        )

    /** The listing of a changed document as the computation builds it: `0.00` money and a true flag when flagged. */
    fun listing(d: ChangedDocument) =
        DeltaListing(
            d.docUuid,
            d.transactionDate,
            d.precedingDocumentDate,
            d.oldOutcome.code,
            d.newOutcome.code,
            if (d.sgdValuesAbsent) ZERO_MONEY else d.taxableValue,
            if (d.sgdValuesAbsent) ZERO_MONEY else d.gstValue,
            d.supplyType,
            if (d.sgdValuesAbsent) true else null,
        )
}
