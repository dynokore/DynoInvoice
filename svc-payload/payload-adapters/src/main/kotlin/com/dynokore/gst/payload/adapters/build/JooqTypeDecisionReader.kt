package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.adapters.persistence.jooq.tables.SubmissionTypeDecision
import com.dynokore.gst.payload.application.build.TypeDecisionReader
import org.jooq.DSLContext
import java.util.UUID

/** The stored submission type of a version for the tenant bound to the request; null when none is stored. */
class JooqTypeDecisionReader(
    private val dsl: DSLContext,
    private val boundTenant: () -> UUID,
) : TypeDecisionReader {
    private val table = SubmissionTypeDecision.SUBMISSION_TYPE_DECISION

    override fun typeOf(
        docUuid: UUID,
        version: Int,
    ): String? =
        dsl
            .select(table.SUBMISSION_TYPE)
            .from(table)
            .where(
                table.TENANT_ID
                    .eq(boundTenant())
                    .and(table.DOC_UUID.eq(docUuid))
                    .and(table.VERSION.eq(version)),
            ).fetchOne(table.SUBMISSION_TYPE)
}
