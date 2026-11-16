package com.dynokore.gst.payload.adapters.persistence

import com.dynokore.gst.payload.adapters.persistence.jooq.tables.SubmissionTypeDecision
import com.dynokore.gst.payload.application.build.TypeDecisions
import org.jooq.DSLContext
import java.util.UUID

/**
 * The submission type decisions over `payload.submission_type_decision`. It opens no transaction: the insert commits
 * with the caller. The first stored type of a version stays.
 */
class JooqTypeDecisionStore(
    private val dsl: DSLContext,
    private val jobs: JooqBuildJobs,
    private val boundTenant: () -> UUID,
) : TypeDecisions {
    private val table = SubmissionTypeDecision.SUBMISSION_TYPE_DECISION

    /** True when a row was newly stored; locks the version first, so the other fact's store waits for this commit. */
    fun store(
        docUuid: UUID,
        version: Int,
        submissionType: String,
        connectionRef: String? = null,
    ): Boolean {
        jobs.lockVersion(docUuid, version)
        return dsl
            .insertInto(table)
            .set(table.TENANT_ID, boundTenant())
            .set(table.DOC_UUID, docUuid)
            .set(table.VERSION, version)
            .set(table.SUBMISSION_TYPE, submissionType)
            .set(table.CONNECTION_REF, connectionRef)
            .onConflictDoNothing()
            .execute() == 1
    }

    override fun exists(
        docUuid: UUID,
        version: Int,
    ): Boolean =
        dsl.fetchExists(
            table,
            table.TENANT_ID
                .eq(boundTenant())
                .and(table.DOC_UUID.eq(docUuid))
                .and(table.VERSION.eq(version)),
        )
}
