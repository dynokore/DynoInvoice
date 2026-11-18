package com.dynokore.gst.reconciliation.adapters.persistence

import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.GST_RETURN
import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.GST_RETURN_BOX
import com.dynokore.gst.reconciliation.application.returns.ReturnRecords
import com.dynokore.gst.reconciliation.application.returns.StoredReturn
import com.dynokore.gst.reconciliation.application.run.ReturnFiguresSource
import com.dynokore.gst.reconciliation.domain.returns.RecordedReturn
import com.dynokore.gst.reconciliation.domain.returns.ReturnForm
import com.dynokore.gst.reconciliation.domain.returns.ReturnVersions
import com.dynokore.gst.reconciliation.domain.run.ReturnFigures
import org.jooq.DSLContext
import java.time.ZoneOffset
import java.util.UUID

/**
 * The append-only return store (INV-T-24367, INV-REQ-653, INV-AC-24366): one `gst_return` row per version and one
 * `gst_return_box` row per Box. The caller binds the tenant and opens the transaction. Every statement names the tenant
 * in its WHERE or VALUES as well as the bound tenant of the row-level security, so a bypassed policy still reads one
 * tenant only. [ReturnFiguresSource.current] is the Boxes of the highest stored version ([ReturnVersions.current]).
 */
class JooqReturnRecords(
    private val dsl: DSLContext,
) : ReturnRecords,
    ReturnFiguresSource {
    override fun recorded(
        tenant: String,
        entityRef: String,
        periodRef: String,
    ): List<StoredReturn> {
        val tenantId = UUID.fromString(tenant)
        val boxes =
            dsl
                .select(
                    GST_RETURN_BOX.VERSION,
                    GST_RETURN_BOX.BOX,
                    GST_RETURN_BOX.AMOUNT,
                ).from(GST_RETURN_BOX)
                .where(GST_RETURN_BOX.TENANT_ID.eq(tenantId))
                .and(GST_RETURN_BOX.ENTITY_REF.eq(entityRef))
                .and(GST_RETURN_BOX.PERIOD_REF.eq(periodRef))
                .fetch()
                .groupBy({ checkNotNull(it.value1()) }, { checkNotNull(it.value2()) to checkNotNull(it.value3()) })
        return dsl
            .selectFrom(GST_RETURN)
            .where(GST_RETURN.TENANT_ID.eq(tenantId))
            .and(GST_RETURN.ENTITY_REF.eq(entityRef))
            .and(GST_RETURN.PERIOD_REF.eq(periodRef))
            .orderBy(GST_RETURN.VERSION.asc())
            .fetch()
            .map { row ->
                StoredReturn(
                    recorded =
                        RecordedReturn(
                            version = checkNotNull(row.version),
                            form = ReturnForm.valueOf(checkNotNull(row.form)),
                            filedAt = checkNotNull(row.filedAt).toInstant(),
                            filingAcknowledgement = checkNotNull(row.filingAcknowledgement),
                            amends = row.amends,
                        ),
                    boxes = boxes[row.version].orEmpty().toMap(linkedMapOf()),
                    filingReference = row.filingReference,
                    preparer = row.preparer,
                    uploadedBy = checkNotNull(row.uploadedBy),
                    recordedAt = checkNotNull(row.recordedAt).toInstant(),
                )
            }
    }

    override fun insertIfAbsent(
        tenant: String,
        entityRef: String,
        periodRef: String,
        stored: StoredReturn,
    ): Boolean {
        val tenantId = UUID.fromString(tenant)
        val version = stored.recorded.version
        val inserted =
            dsl
                .insertInto(GST_RETURN)
                .set(GST_RETURN.TENANT_ID, tenantId)
                .set(GST_RETURN.ENTITY_REF, entityRef)
                .set(GST_RETURN.PERIOD_REF, periodRef)
                .set(GST_RETURN.VERSION, version)
                .set(GST_RETURN.FORM, stored.recorded.form.value)
                .set(GST_RETURN.FILED_AT, stored.recorded.filedAt.atOffset(ZoneOffset.UTC))
                .set(GST_RETURN.FILING_ACKNOWLEDGEMENT, stored.recorded.filingAcknowledgement)
                .set(GST_RETURN.FILING_REFERENCE, stored.filingReference)
                .set(GST_RETURN.PREPARER, stored.preparer)
                .set(GST_RETURN.UPLOADED_BY, stored.uploadedBy)
                .set(GST_RETURN.AMENDS, stored.recorded.amends)
                .set(GST_RETURN.RECORDED_AT, stored.recordedAt.atOffset(ZoneOffset.UTC))
                .onConflictDoNothing()
                .execute() == 1
        if (inserted) {
            stored.boxes.forEach { (box, amount) ->
                dsl
                    .insertInto(GST_RETURN_BOX)
                    .set(GST_RETURN_BOX.TENANT_ID, tenantId)
                    .set(GST_RETURN_BOX.ENTITY_REF, entityRef)
                    .set(GST_RETURN_BOX.PERIOD_REF, periodRef)
                    .set(GST_RETURN_BOX.VERSION, version)
                    .set(GST_RETURN_BOX.BOX, box)
                    .set(GST_RETURN_BOX.AMOUNT, amount)
                    .execute()
            }
        }
        return inserted
    }

    override fun current(
        tenant: String,
        entityRef: String,
        periodRef: String,
    ): ReturnFigures? {
        val stored = recorded(tenant, entityRef, periodRef)
        val current = ReturnVersions.current(stored.map { it.recorded }) ?: return null
        return ReturnFigures(current.version, stored.first { it.recorded.version == current.version }.boxes)
    }
}
