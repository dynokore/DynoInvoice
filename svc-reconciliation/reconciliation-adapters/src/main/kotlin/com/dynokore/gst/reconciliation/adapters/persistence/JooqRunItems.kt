package com.dynokore.gst.reconciliation.adapters.persistence

import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.RECONCILIATION_RUN_ITEM
import com.dynokore.gst.reconciliation.application.run.RunItems
import com.dynokore.gst.reconciliation.domain.run.ReconcilingItem
import org.jooq.DSLContext
import java.math.RoundingMode
import java.util.UUID

/**
 * The stored reconciling items of a run (INV-T-24464, INV-REQ-982, INV-AC-24463): insert-only rows written by
 * `JooqCompletedRuns.record` in its own transaction, never in a second one, and read back ordered by box, docUuid,
 * version, lineNumber. Every statement names the tenant as well as the bound tenant of the row-level security.
 */
class JooqRunItems(
    private val dsl: DSLContext,
) : RunItems {
    /** Inserts the items of the run just recorded, on the connection and transaction of [dsl]. */
    fun write(
        tenantId: UUID,
        runId: UUID,
        items: List<ReconcilingItem>,
    ) {
        items.forEach { item ->
            dsl
                .insertInto(RECONCILIATION_RUN_ITEM)
                .set(RECONCILIATION_RUN_ITEM.TENANT_ID, tenantId)
                .set(RECONCILIATION_RUN_ITEM.RUN_ID, runId)
                .set(RECONCILIATION_RUN_ITEM.BOX, item.box)
                .set(RECONCILIATION_RUN_ITEM.DOC_UUID, item.docUuid)
                .set(RECONCILIATION_RUN_ITEM.VERSION, item.version)
                .set(RECONCILIATION_RUN_ITEM.LINE_NUMBER, item.lineNumber)
                .set(RECONCILIATION_RUN_ITEM.AMOUNT, item.amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY))
                .set(RECONCILIATION_RUN_ITEM.EVIDENCE_REFS, item.evidenceRefs.toTypedArray())
                .execute()
        }
    }

    override fun items(
        tenant: String,
        runId: String,
    ): List<ReconcilingItem> =
        dsl
            .selectFrom(RECONCILIATION_RUN_ITEM)
            .where(RECONCILIATION_RUN_ITEM.TENANT_ID.eq(UUID.fromString(tenant)))
            .and(RECONCILIATION_RUN_ITEM.RUN_ID.eq(UUID.fromString(runId)))
            .orderBy(
                RECONCILIATION_RUN_ITEM.BOX,
                RECONCILIATION_RUN_ITEM.DOC_UUID,
                RECONCILIATION_RUN_ITEM.VERSION,
                RECONCILIATION_RUN_ITEM.LINE_NUMBER,
            ).fetch()
            .map {
                ReconcilingItem(
                    box = it.get(RECONCILIATION_RUN_ITEM.BOX).orEmpty(),
                    docUuid = it.get(RECONCILIATION_RUN_ITEM.DOC_UUID).orEmpty(),
                    version = checkNotNull(it.get(RECONCILIATION_RUN_ITEM.VERSION)),
                    lineNumber = checkNotNull(it.get(RECONCILIATION_RUN_ITEM.LINE_NUMBER)),
                    amount = checkNotNull(it.get(RECONCILIATION_RUN_ITEM.AMOUNT)),
                    evidenceRefs = it.get(RECONCILIATION_RUN_ITEM.EVIDENCE_REFS).orEmpty().filterNotNull(),
                )
            }

    private companion object {
        const val MONEY_SCALE = 2
    }
}
