package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.queue.SubmissionStore
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext

/** [SubmissionStore] over the generated jOOQ classes of `submission` (CS-18), in the open tenant transaction. */
class JooqSubmissionStore(
    private val dsl: DSLContext,
) : SubmissionStore {
    override fun moveReadyToQueued(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): Int =
        dsl
            .update(Submission.SUBMISSION_)
            .set(Submission.SUBMISSION_.STATE, "QUEUED")
            .where(Submission.SUBMISSION_.TENANT_ID.eq(tenant.value))
            .and(Submission.SUBMISSION_.DOC_UUID.eq(docUuid))
            .and(Submission.SUBMISSION_.VERSION.eq(version))
            .and(Submission.SUBMISSION_.STATE.eq("READY"))
            .execute()
}
