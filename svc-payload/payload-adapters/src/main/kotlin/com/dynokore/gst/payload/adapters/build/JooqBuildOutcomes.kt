package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.adapters.persistence.jooq.tables.BuildOutcome
import com.dynokore.gst.payload.application.build.BuildOutcomes
import org.jooq.DSLContext
import java.util.UUID

/**
 * The once-only record of a build over `payload.build_outcome` (idempotent consumer, microservices.io). It opens no
 * transaction: the insert commits or rolls back with the build that staged the event.
 */
class JooqBuildOutcomes(
    private val dsl: DSLContext,
    private val boundTenant: () -> UUID,
) : BuildOutcomes {
    private val table = BuildOutcome.BUILD_OUTCOME

    override fun firstTime(
        docUuid: UUID,
        version: Int,
    ): Boolean =
        dsl
            .insertInto(table)
            .set(table.TENANT_ID, boundTenant())
            .set(table.DOC_UUID, docUuid)
            .set(table.VERSION, version)
            .set(table.OUTCOME, BUILDING)
            .onConflictDoNothing()
            .execute() == 1

    companion object {
        const val BUILDING = "BUILDING"
    }
}
