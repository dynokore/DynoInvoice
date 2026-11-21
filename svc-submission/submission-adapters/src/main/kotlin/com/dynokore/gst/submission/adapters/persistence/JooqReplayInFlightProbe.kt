package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.replay.ReplayInFlightProbe
import com.dynokore.gst.submission.domain.ordering.InFlight
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext

/**
 * [ReplayInFlightProbe] (INV-REQ-1012): whether a version of the Doc UUID other than the named one is in one of the
 * in-flight states (`InFlight.NAMES`, the predicate of the index `submission_in_flight_uq`). One exists-select in one
 * read transaction, tenant in the WHERE clause as well as the row level security (CS-19). It takes no lock and
 * writes nothing; the write path decides the same conflict under lock.
 */
class JooqReplayInFlightProbe(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : ReplayInFlightProbe {
    private val version = Submission.SUBMISSION_

    override fun hasOtherInFlight(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): Boolean =
        runner.inTransaction {
            dsl.fetchExists(
                dsl
                    .selectOne()
                    .from(this.version)
                    .where(this.version.TENANT_ID.eq(tenant.value))
                    .and(this.version.DOC_UUID.eq(docUuid))
                    .and(this.version.VERSION.ne(version))
                    .and(this.version.STATE.`in`(InFlight.NAMES)),
            )
        }
}
