package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.arrival.ScopeBlocks
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext

/**
 * [ScopeBlocks] over `submission.submission` (INV-T-24506): one guarded `UPDATE` whose WHERE holds tenant, Doc UUID,
 * version and state READY (optimistic state transition). It opens no transaction; the caller's handler owns it and
 * forced row level security applies as well.
 */
class JooqScopeBlocks(
    private val dsl: DSLContext,
) : ScopeBlocks {
    private val s = Submission.SUBMISSION_

    override fun blockReady(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): Int =
        dsl
            .update(s)
            .set(s.STATE, "BLOCKED")
            .where(s.TENANT_ID.eq(tenant.value))
            .and(s.DOC_UUID.eq(docUuid))
            .and(s.VERSION.eq(version))
            .and(s.STATE.eq("READY"))
            .execute()
}
