package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.SubmissionAttempt
import com.dynokore.gst.submission.application.queue.FailureCodes
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext

/**
 * [FailureCodes] over the `failure_class` column of `submission.submission_attempt` (INV-REQ-940, INV-REQ-575), in the
 * open tenant transaction. The tenant is in every predicate, so another tenant's row is never read or written.
 */
class JooqFailureCodes(
    private val dsl: DSLContext,
) : FailureCodes {
    override fun record(
        tenant: TenantId,
        docUuid: String,
        version: Int,
        attemptNumber: Int,
        code: String,
    ) {
        val a = SubmissionAttempt.SUBMISSION_ATTEMPT
        val updated =
            dsl
                .update(a)
                .set(a.FAILURE_CLASS, code)
                .where(a.TENANT_ID.eq(tenant.value))
                .and(a.DOC_UUID.eq(docUuid))
                .and(a.VERSION.eq(version))
                .and(a.ATTEMPT_NUMBER.eq(attemptNumber))
                .execute()
        check(updated == 1) { "no attempt row $attemptNumber of $docUuid version $version" }
    }

    override fun lastOf(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): String? {
        val a = SubmissionAttempt.SUBMISSION_ATTEMPT
        return dsl
            .select(a.FAILURE_CLASS)
            .from(a)
            .where(a.TENANT_ID.eq(tenant.value))
            .and(a.DOC_UUID.eq(docUuid))
            .and(a.VERSION.eq(version))
            .orderBy(a.ATTEMPT_NUMBER.desc())
            .limit(1)
            .fetchOne(a.FAILURE_CLASS)
    }
}
