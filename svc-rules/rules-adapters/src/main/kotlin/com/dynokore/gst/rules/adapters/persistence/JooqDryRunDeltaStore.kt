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
            val tx = DSL.using(configuration)
            val straddling = delta.straddlingCreditNotes.map { it.docUuid }.toSet()
            delta.documents.forEachIndexed { index, doc ->
                tx
                    .insertInto(DRY_RUN_DELTA_DOCUMENT)
                    .set(DRY_RUN_DELTA_DOCUMENT.TENANT_ID, tenant)
                    .set(DRY_RUN_DELTA_DOCUMENT.DELTA_ID, id)
                    .set(DRY_RUN_DELTA_DOCUMENT.POSITION, index + 1)
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
                    .set(DRY_RUN_DELTA_DOCUMENT.STRADDLING_CREDIT_NOTE, doc.docUuid in straddling)
                    .execute()
            }
            delta.changeTypeTotals.forEachIndexed { index, t ->
                insertTotal(
                    tx, tenant, id, CHANGE_TYPE, t.changeType.name, index + 1,
                    t.documentCount, t.valuesAbsentCount, t.taxableValue, t.gstValue,
                )
            }
            delta.byPeriod.forEachIndexed { index, p ->
                insertTotal(
                    tx, tenant, id, PERIOD, p.periodRef, index + 1,
                    p.documentCount, p.valuesAbsentCount, p.taxableValue, p.gstValue,
                )
            }
            val updated =
                tx
                    .update(DRY_RUN_DELTA)
                    .set(DRY_RUN_DELTA.STATE, DryRunState.COMPLETE.name)
                    .set(DRY_RUN_DELTA.COMPLETED_AT, completedAt.atOffset(ZoneOffset.UTC))
                    .set(DRY_RUN_DELTA.CHANGED_COUNT, delta.documents.size)
                    .where(DRY_RUN_DELTA.ID.eq(id))
                    .and(DRY_RUN_DELTA.TENANT_ID.eq(tenant))
                    .and(DRY_RUN_DELTA.STATE.eq(DryRunState.RUNNING.name))
                    .execute()
            check(updated == 1) { "dry run $dryRunId is not a RUNNING run of this tenant" }
        }
    }

    @Suppress("LongParameterList")
    private fun insertTotal(
        tx: DSLContext,
        tenant: UUID,
        id: UUID,
        kind: String,
        key: String,
        position: Int,
        documentCount: Int,
        valuesAbsentCount: Int,
        taxable: String,
        gst: String,
    ) {
        tx
            .insertInto(DRY_RUN_DELTA_TOTAL)
            .set(DRY_RUN_DELTA_TOTAL.TENANT_ID, tenant)
            .set(DRY_RUN_DELTA_TOTAL.DELTA_ID, id)
            .set(DRY_RUN_DELTA_TOTAL.KIND, kind)
            .set(DRY_RUN_DELTA_TOTAL.KEY, key)
            .set(DRY_RUN_DELTA_TOTAL.POSITION, position)
            .set(DRY_RUN_DELTA_TOTAL.DOCUMENT_COUNT, documentCount)
            .set(DRY_RUN_DELTA_TOTAL.VALUES_ABSENT_COUNT, valuesAbsentCount)
            .set(DRY_RUN_DELTA_TOTAL.TAXABLE_VALUE, BigDecimal(taxable))
            .set(DRY_RUN_DELTA_TOTAL.GST_VALUE, BigDecimal(gst))
            .execute()
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
        val id = runCatching { UUID.fromString(dryRunId) }.getOrNull() ?: return null
        val run =
            dsl
                .selectFrom(DRY_RUN_DELTA)
                .where(DRY_RUN_DELTA.ID.eq(id))
                .and(DRY_RUN_DELTA.TENANT_ID.eq(tenants.current().value))
                .fetchOne() ?: return null
        val state = DryRunState.valueOf(run.state!!)
        return StoredDryRun(
            dryRunId = id.toString(),
            startedBy = run.startedBy!!,
            state = state,
            reason = run.unavailableReason,
            delta = if (state == DryRunState.COMPLETE) rebuild(id) else null,
        )
    }

    private fun rebuild(id: UUID): DryRunDelta {
        val rows =
            dsl
                .selectFrom(DRY_RUN_DELTA_DOCUMENT)
                .where(DRY_RUN_DELTA_DOCUMENT.DELTA_ID.eq(id))
                .orderBy(DRY_RUN_DELTA_DOCUMENT.POSITION)
                .fetch()
        val documents =
            rows.map { r ->
                ChangedDocument(
                    docUuid = r.docUuid!!.toString(),
                    periodRef = r.periodRef!!,
                    transactionDate = r.transactionDate!!,
                    precedingDocumentDate = r.precedingDocumentDate,
                    documentFunction = r.documentFunction!!,
                    supplyType = r.supplyType,
                    oldOutcome = DocumentOutcome(r.oldCode!!, r.oldSeverity),
                    newOutcome = DocumentOutcome(r.newCode!!, r.newSeverity),
                    changeType = ChangeType.valueOf(r.changeType!!),
                    taxableValue = r.taxableValue!!.toPlainString(),
                    gstValue = r.gstValue!!.toPlainString(),
                    sgdValuesAbsent = r.valuesAbsent!!,
                )
            }
        val straddling = rows.filter { it.straddlingCreditNote!! }.map { it.docUuid!!.toString() }.toSet()
        val totals =
            dsl
                .selectFrom(DRY_RUN_DELTA_TOTAL)
                .where(DRY_RUN_DELTA_TOTAL.DELTA_ID.eq(id))
                .orderBy(DRY_RUN_DELTA_TOTAL.KIND, DRY_RUN_DELTA_TOTAL.POSITION)
                .fetch()
        return DryRunDelta(
            changedCount = documents.size,
            documents = documents,
            changeTypeTotals =
                totals.filter { it.kind == CHANGE_TYPE }.map {
                    ChangeTypeTotal(
                        ChangeType.valueOf(it.key!!),
                        it.documentCount!!,
                        it.taxableValue!!.toPlainString(),
                        it.gstValue!!.toPlainString(),
                        it.valuesAbsentCount!!,
                    )
                },
            byPeriod =
                totals.filter { it.kind == PERIOD }.map {
                    PeriodDelta(
                        it.key!!,
                        it.documentCount!!,
                        it.taxableValue!!.toPlainString(),
                        it.gstValue!!.toPlainString(),
                        it.valuesAbsentCount!!,
                    )
                },
            straddlingCreditNotes = documents.filter { it.docUuid in straddling }.map(::listing),
            sample = documents.filter { it.docUuid !in straddling }.map(::listing),
        )
    }

    /** The listing of a changed document, as the computation builds it: money is `0.00` and the flag true when flagged. */
    private fun listing(d: ChangedDocument) =
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

    private companion object {
        const val CHANGE_TYPE = "CHANGE_TYPE"
        const val PERIOD = "PERIOD"
        const val ZERO_MONEY = "0.00"
    }
}
