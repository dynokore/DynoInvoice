package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.SubmissionAttempt
import com.dynokore.gst.submission.application.queue.LatestAttempt
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** [LatestAttempt] over `submission.submission_attempt`; the tenant is in the predicate, as [JooqFailureCodes]. */
class JooqLatestAttempt(
    private val dsl: DSLContext,
) : LatestAttempt {
    override fun numberOf(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): Int? {
        val a = SubmissionAttempt.SUBMISSION_ATTEMPT
        return dsl
            .select(DSL.max(a.ATTEMPT_NUMBER))
            .from(a)
            .where(a.TENANT_ID.eq(tenant.value))
            .and(a.DOC_UUID.eq(docUuid))
            .and(a.VERSION.eq(version))
            .fetchOne(0, Int::class.javaObjectType)
    }
}
