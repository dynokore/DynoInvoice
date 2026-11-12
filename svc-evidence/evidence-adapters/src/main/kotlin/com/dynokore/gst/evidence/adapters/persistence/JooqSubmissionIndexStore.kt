package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.SubmissionIndex.Companion.SUBMISSION_INDEX
import com.dynokore.gst.evidence.application.chain.SubmissionIndexRow
import com.dynokore.gst.evidence.application.chain.SubmissionIndexStore
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** `submission_index` over the service's tenant-bound [DSLContext] (jOOQ only, STK §3). */
class JooqSubmissionIndexStore(
    private val dsl: DSLContext,
) : SubmissionIndexStore {
    override fun upsertDocument(
        tenant: TenantId,
        row: SubmissionIndexRow,
    ): Boolean {
        val t = SUBMISSION_INDEX
        return dsl
            .insertInto(t)
            .set(t.TENANT_ID, tenant.value)
            .set(t.DOC_UUID, row.docUuid)
            .set(t.VERSION, row.version)
            .set(t.ENTITY_REF, row.entityRef)
            .set(t.PERIOD_REF, row.periodRef)
            .set(t.DOCUMENT_NUMBER, row.documentNumber)
            .set(t.DOCUMENT_DATE, row.documentDate)
            .set(t.COUNTERPARTY_UEN, row.counterpartyUen)
            .set(t.RECORDED_AT, row.recordedAt.utc())
            .onConflict(t.TENANT_ID, t.DOC_UUID, t.VERSION)
            .doUpdate()
            .set(t.ENTITY_REF, DSL.coalesce(t.ENTITY_REF, DSL.excluded(t.ENTITY_REF)))
            .set(t.PERIOD_REF, DSL.coalesce(t.PERIOD_REF, DSL.excluded(t.PERIOD_REF)))
            .set(t.DOCUMENT_NUMBER, DSL.coalesce(t.DOCUMENT_NUMBER, DSL.excluded(t.DOCUMENT_NUMBER)))
            .set(t.DOCUMENT_DATE, DSL.coalesce(t.DOCUMENT_DATE, DSL.excluded(t.DOCUMENT_DATE)))
            .set(t.COUNTERPARTY_UEN, DSL.coalesce(t.COUNTERPARTY_UEN, DSL.excluded(t.COUNTERPARTY_UEN)))
            .where(t.DOCUMENT_DATE.isNull)
            .execute() == 1
    }
}
