package com.dynokore.gst.reconciliation.adapters.persistence

import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.DOCUMENT_ACKNOWLEDGEMENT
import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.DOCUMENT_LINE
import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.DOCUMENT_PERIOD
import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.DOCUMENT_VERSION
import com.dynokore.gst.reconciliation.application.run.DocumentVersionFacts
import com.dynokore.gst.reconciliation.application.run.RunDocumentsSource
import com.dynokore.gst.reconciliation.application.run.RunReadModelWriter
import com.dynokore.gst.reconciliation.domain.boxes.Direction
import com.dynokore.gst.reconciliation.domain.run.RunDocument
import com.dynokore.gst.reconciliation.domain.run.RunDocumentVersion
import com.dynokore.gst.reconciliation.domain.sgd.AllocationLine
import com.dynokore.gst.reconciliation.domain.sgd.SgdAllocator
import com.dynokore.gst.reconciliation.domain.sgd.StatedSgd
import com.dynokore.gst.reconciliation.domain.variance.DocumentFacts
import org.jooq.DSLContext
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The run read model store (INV-T-24344, INV-AC-24341, INV-REQ-1206): the writer the consumers fill and the source the
 * run reads. Writes are idempotent (insert on conflict do nothing); a received row is never updated; the current
 * period of a document moves only to a higher docsequence. The caller binds the tenant and opens the transaction.
 */
class JooqRunReadModel(
    private val dsl: DSLContext,
) : RunReadModelWriter,
    RunDocumentsSource {
    override fun recordVersion(
        tenant: String,
        entityRef: String,
        facts: DocumentVersionFacts,
    ): Boolean {
        val tenantId = UUID.fromString(tenant)
        val docId = UUID.fromString(facts.docUuid)
        val inserted =
            dsl
                .insertInto(DOCUMENT_VERSION)
                .set(DOCUMENT_VERSION.TENANT_ID, tenantId)
                .set(DOCUMENT_VERSION.DOC_UUID, docId)
                .set(DOCUMENT_VERSION.VERSION, facts.version)
                .set(DOCUMENT_VERSION.ENTITY_REF, entityRef)
                .set(DOCUMENT_VERSION.TRANSACTION_DATE, facts.transactionDate)
                .set(DOCUMENT_VERSION.SCOPE_VERDICT, facts.scopeVerdict)
                .set(DOCUMENT_VERSION.NOT_SUBMITTABLE_REASON, facts.notSubmittableReason)
                .set(DOCUMENT_VERSION.DIRECTION, facts.direction.name)
                .set(DOCUMENT_VERSION.CURRENCY, facts.currency)
                .set(DOCUMENT_VERSION.EXCHANGE_RATE, facts.exchangeRate)
                .set(DOCUMENT_VERSION.SGD_TOTAL_EXCLUDING_TAX, facts.sgdTotalExcludingTax)
                .set(DOCUMENT_VERSION.SGD_TOTAL_TAX, facts.sgdTotalTax)
                .set(DOCUMENT_VERSION.RECEIVED_AT, Instant.now().atOffset(ZoneOffset.UTC))
                .onConflictDoNothing()
                .execute()
        if (inserted == 0) return false
        facts.lines.forEach { line ->
            dsl
                .insertInto(DOCUMENT_LINE)
                .set(DOCUMENT_LINE.TENANT_ID, tenantId)
                .set(DOCUMENT_LINE.DOC_UUID, docId)
                .set(DOCUMENT_LINE.VERSION, facts.version)
                .set(DOCUMENT_LINE.LINE_NUMBER, line.lineNumber)
                .set(DOCUMENT_LINE.TAX_CODE, line.taxCode)
                .set(DOCUMENT_LINE.LINE_AMOUNT, line.lineAmount)
                .set(DOCUMENT_LINE.TAX_AMOUNT, line.taxAmount)
                .execute()
        }
        upsertPeriod(tenantId, entityRef, docId, facts.docsequence, facts.periodRef)
        return true
    }

    override fun reassignPeriod(
        tenant: String,
        entityRef: String,
        docUuid: String,
        docsequence: Long,
        newPeriodRef: String,
    ): Boolean = upsertPeriod(UUID.fromString(tenant), entityRef, UUID.fromString(docUuid), docsequence, newPeriodRef)

    /** Inserts the row, or moves it only when the stored docsequence is lower; true when a row was written. */
    private fun upsertPeriod(
        tenantId: UUID,
        entityRef: String,
        docId: UUID,
        docsequence: Long,
        periodRef: String,
    ): Boolean =
        dsl
            .insertInto(DOCUMENT_PERIOD)
            .set(DOCUMENT_PERIOD.TENANT_ID, tenantId)
            .set(DOCUMENT_PERIOD.DOC_UUID, docId)
            .set(DOCUMENT_PERIOD.ENTITY_REF, entityRef)
            .set(DOCUMENT_PERIOD.PERIOD_REF, periodRef)
            .set(DOCUMENT_PERIOD.DOCSEQUENCE, docsequence)
            .onConflict(DOCUMENT_PERIOD.TENANT_ID, DOCUMENT_PERIOD.DOC_UUID)
            .doUpdate()
            .set(DOCUMENT_PERIOD.ENTITY_REF, entityRef)
            .set(DOCUMENT_PERIOD.PERIOD_REF, periodRef)
            .set(DOCUMENT_PERIOD.DOCSEQUENCE, docsequence)
            .where(DOCUMENT_PERIOD.DOCSEQUENCE.lt(docsequence))
            .execute() > 0

    override fun recordAcknowledgement(
        tenant: String,
        docUuid: String,
        version: Int,
        acknowledgementId: String,
        acknowledgedAt: Instant,
    ): Boolean =
        dsl
            .insertInto(DOCUMENT_ACKNOWLEDGEMENT)
            .set(DOCUMENT_ACKNOWLEDGEMENT.TENANT_ID, UUID.fromString(tenant))
            .set(DOCUMENT_ACKNOWLEDGEMENT.DOC_UUID, UUID.fromString(docUuid))
            .set(DOCUMENT_ACKNOWLEDGEMENT.VERSION, version)
            .set(DOCUMENT_ACKNOWLEDGEMENT.ACKNOWLEDGEMENT_ID, acknowledgementId)
            .set(DOCUMENT_ACKNOWLEDGEMENT.ACKNOWLEDGED_AT, acknowledgedAt.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() > 0

    override fun load(
        tenant: String,
        entityRef: String,
        periodRef: String,
    ): List<RunDocument> {
        val tenantId = UUID.fromString(tenant)
        val ack = DOCUMENT_ACKNOWLEDGEMENT
        val acknowledged = ack.ACKNOWLEDGEMENT_ID.isNotNull
        val cursor =
            dsl
                .select(
                    DOCUMENT_VERSION.DOC_UUID,
                    DOCUMENT_VERSION.VERSION,
                    DOCUMENT_VERSION.SCOPE_VERDICT,
                    DOCUMENT_VERSION.NOT_SUBMITTABLE_REASON,
                    DOCUMENT_VERSION.DIRECTION,
                    DOCUMENT_VERSION.CURRENCY,
                    DOCUMENT_VERSION.EXCHANGE_RATE,
                    DOCUMENT_VERSION.SGD_TOTAL_EXCLUDING_TAX,
                    DOCUMENT_VERSION.SGD_TOTAL_TAX,
                    acknowledged,
                    DOCUMENT_LINE.LINE_NUMBER,
                    DOCUMENT_LINE.TAX_CODE,
                    DOCUMENT_LINE.LINE_AMOUNT,
                    DOCUMENT_LINE.TAX_AMOUNT,
                ).from(DOCUMENT_PERIOD)
                .join(DOCUMENT_VERSION)
                .on(DOCUMENT_VERSION.TENANT_ID.eq(DOCUMENT_PERIOD.TENANT_ID))
                .and(DOCUMENT_VERSION.DOC_UUID.eq(DOCUMENT_PERIOD.DOC_UUID))
                .leftJoin(DOCUMENT_LINE)
                .on(DOCUMENT_LINE.TENANT_ID.eq(DOCUMENT_VERSION.TENANT_ID))
                .and(DOCUMENT_LINE.DOC_UUID.eq(DOCUMENT_VERSION.DOC_UUID))
                .and(DOCUMENT_LINE.VERSION.eq(DOCUMENT_VERSION.VERSION))
                .leftJoin(ack)
                .on(ack.TENANT_ID.eq(DOCUMENT_VERSION.TENANT_ID))
                .and(ack.DOC_UUID.eq(DOCUMENT_VERSION.DOC_UUID))
                .and(ack.VERSION.eq(DOCUMENT_VERSION.VERSION))
                .where(DOCUMENT_PERIOD.TENANT_ID.eq(tenantId))
                .and(DOCUMENT_PERIOD.ENTITY_REF.eq(entityRef))
                .and(DOCUMENT_PERIOD.PERIOD_REF.eq(periodRef))
                .orderBy(DOCUMENT_VERSION.DOC_UUID, DOCUMENT_VERSION.VERSION, DOCUMENT_LINE.LINE_NUMBER)
                .fetchSize(FETCH_SIZE)
                .fetchLazy()
        val documents = LinkedHashMap<UUID, MutableMap<Int, PendingVersion>>()
        cursor.use {
            for (row in it) {
                val doc = checkNotNull(row.get(DOCUMENT_VERSION.DOC_UUID))
                val version = checkNotNull(row.get(DOCUMENT_VERSION.VERSION))
                val pending =
                    documents.getOrPut(doc) { LinkedHashMap() }.getOrPut(version) {
                        PendingVersion(
                            version = version,
                            acknowledged = row.get(acknowledged) == true,
                            facts =
                                DocumentFacts(
                                    row.get(DOCUMENT_VERSION.SCOPE_VERDICT),
                                    row.get(DOCUMENT_VERSION.NOT_SUBMITTABLE_REASON),
                                ),
                            direction = Direction.valueOf(checkNotNull(row.get(DOCUMENT_VERSION.DIRECTION))),
                            currency = row.get(DOCUMENT_VERSION.CURRENCY).orEmpty(),
                            stated =
                                stated(
                                    row.get(DOCUMENT_VERSION.EXCHANGE_RATE),
                                    row.get(DOCUMENT_VERSION.SGD_TOTAL_EXCLUDING_TAX),
                                    row.get(DOCUMENT_VERSION.SGD_TOTAL_TAX),
                                ),
                        )
                    }
                row.get(DOCUMENT_LINE.LINE_NUMBER)?.let { number ->
                    pending.lines +=
                        AllocationLine(
                            lineNumber = number,
                            lineAmount = row.get(DOCUMENT_LINE.LINE_AMOUNT) ?: BigDecimal.ZERO,
                            taxAmount = row.get(DOCUMENT_LINE.TAX_AMOUNT) ?: BigDecimal.ZERO,
                            taxCode = row.get(DOCUMENT_LINE.TAX_CODE).orEmpty(),
                        )
                }
            }
        }
        return documents.map { (doc, versions) ->
            RunDocument(
                doc.toString(),
                versions.values.map {
                    RunDocumentVersion(
                        it.version,
                        it.acknowledged,
                        it.facts,
                        it.direction,
                        SgdAllocator.allocate(it.currency, it.lines, it.stated),
                    )
                },
            )
        }
    }

    private fun stated(
        rate: BigDecimal?,
        excludingTax: BigDecimal?,
        tax: BigDecimal?,
    ): StatedSgd? =
        if (rate == null || excludingTax == null ||
            tax == null
        ) {
            null
        } else {
            StatedSgd(excludingTax, tax, rate)
        }

    private class PendingVersion(
        val version: Int,
        val acknowledged: Boolean,
        val facts: DocumentFacts,
        val direction: Direction,
        val currency: String,
        val stated: StatedSgd?,
        val lines: MutableList<AllocationLine> = mutableListOf(),
    )

    private companion object {
        const val FETCH_SIZE = 500
    }
}
