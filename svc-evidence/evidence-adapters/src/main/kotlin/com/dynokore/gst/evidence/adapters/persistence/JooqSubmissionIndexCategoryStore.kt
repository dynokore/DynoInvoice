package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.SubmissionIndexCategory.Companion.SUBMISSION_INDEX_CATEGORY
import com.dynokore.gst.evidence.application.chain.SubmissionIndexCategoryStore
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.util.UUID

/** `submission_index_category` over the service's tenant-bound [DSLContext] (jOOQ only, STK §3). */
class JooqSubmissionIndexCategoryStore(
    private val dsl: DSLContext,
) : SubmissionIndexCategoryStore {
    override fun insertAll(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
        codes: Set<String>,
    ) {
        val t = SUBMISSION_INDEX_CATEGORY
        codes.forEach { code ->
            dsl
                .insertInto(t)
                .set(t.TENANT_ID, tenant.value)
                .set(t.DOC_UUID, docUuid)
                .set(t.VERSION, version)
                .set(t.CATEGORY_CODE, code)
                .onConflictDoNothing()
                .execute()
        }
    }
}
