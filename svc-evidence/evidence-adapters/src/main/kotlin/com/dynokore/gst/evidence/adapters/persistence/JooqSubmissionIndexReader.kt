package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.SubmissionIndex.Companion.SUBMISSION_INDEX
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.SubmissionIndexCategory.Companion.SUBMISSION_INDEX_CATEGORY
import com.dynokore.gst.evidence.application.lookup.ReverseLookupFilter
import com.dynokore.gst.evidence.application.lookup.ReverseLookupKey
import com.dynokore.gst.evidence.application.lookup.ReverseLookupRow
import com.dynokore.gst.evidence.application.lookup.SubmissionIndexReader
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * `submission_index` read over the service's tenant-bound [DSLContext] (jOOQ only, STK §3; INV-REQ-724). Only complete
 * rows are read: `document_date` present (a partial acknowledgement-only row stays hidden, round h ruling 3) and
 * `state` present (a version never despatched is not a submission, round k ruling 11). The category code matches
 * through `exists` on the child table, so a version with two codes is returned once.
 */
class JooqSubmissionIndexReader(
    private val dsl: DSLContext,
) : SubmissionIndexReader {
    override fun read(
        tenant: TenantId,
        filter: ReverseLookupFilter,
        after: ReverseLookupKey?,
        limit: Int,
    ): List<ReverseLookupRow> {
        val i = SUBMISSION_INDEX
        return dsl
            .select(
                i.DOCUMENT_DATE,
                i.DOC_UUID,
                i.VERSION,
                i.STATE,
                i.ACK_ID,
                i.ACKNOWLEDGED_AT,
                i.ENTITY_REF,
                i.PERIOD_REF,
            ).from(i)
            .where(conditions(tenant, filter, after))
            .orderBy(i.DOCUMENT_DATE, i.DOC_UUID, i.VERSION)
            .limit(limit)
            .fetch()
            .map {
                ReverseLookupRow(
                    ReverseLookupKey(
                        checkNotNull(it[i.DOCUMENT_DATE]),
                        checkNotNull(it[i.DOC_UUID]),
                        checkNotNull(it[i.VERSION]),
                    ),
                    checkNotNull(it[i.STATE]),
                    it[i.ACK_ID],
                    it[i.ACKNOWLEDGED_AT]?.toInstant(),
                    it[i.ENTITY_REF],
                    it[i.PERIOD_REF],
                )
            }
    }

    private fun conditions(
        tenant: TenantId,
        filter: ReverseLookupFilter,
        after: ReverseLookupKey?,
    ): List<Condition> {
        val i = SUBMISSION_INDEX
        return listOfNotNull<Condition>(
            i.TENANT_ID.eq(tenant.value),
            i.DOCUMENT_DATE.isNotNull,
            i.STATE.isNotNull,
            filter.counterparty?.let { i.COUNTERPARTY_UEN.eq(it) },
            filter.documentNumber?.let { i.DOCUMENT_NUMBER.eq(it) },
            filter.docUuid?.let { i.DOC_UUID.eq(it) },
            filter.periodRef?.let { i.PERIOD_REF.eq(it) },
            filter.dateFrom?.let { i.DOCUMENT_DATE.ge(it) },
            filter.dateTo?.let { i.DOCUMENT_DATE.le(it) },
            filter.categoryCode?.let { code ->
                val c = SUBMISSION_INDEX_CATEGORY
                DSL.exists(
                    DSL
                        .selectOne()
                        .from(c)
                        .where(c.TENANT_ID.eq(tenant.value))
                        .and(c.DOC_UUID.eq(i.DOC_UUID))
                        .and(c.VERSION.eq(i.VERSION))
                        .and(c.CATEGORY_CODE.eq(code)),
                )
            },
            after?.let {
                DSL.row(i.DOCUMENT_DATE, i.DOC_UUID, i.VERSION).gt(DSL.row(it.documentDate, it.docUuid, it.version))
            },
        )
    }
}
