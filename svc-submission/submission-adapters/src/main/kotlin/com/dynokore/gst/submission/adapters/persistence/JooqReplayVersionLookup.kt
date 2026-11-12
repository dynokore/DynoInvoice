package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.replay.ReplayVersionLookup
import com.dynokore.gst.submission.application.replay.ReplayVersionView
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext

/**
 * [ReplayVersionLookup] (INV-REQ-575): reads `entity_ref` and `state` of the one row (tenant, Doc UUID, version) in one
 * read transaction of the bound tenant, tenant in the WHERE clause as well as the row level security (CS-19).
 * Writes nothing.
 */
class JooqReplayVersionLookup(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : ReplayVersionLookup {
    private val row = Submission.SUBMISSION_

    override fun find(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): ReplayVersionView? =
        runner
            .inTransaction {
                dsl
                    .select(row.ENTITY_REF, row.STATE)
                    .from(row)
                    .where(row.TENANT_ID.eq(tenant.value))
                    .and(row.DOC_UUID.eq(docUuid))
                    .and(row.VERSION.eq(version))
                    .fetchOne()
            }?.let {
                ReplayVersionView(
                    checkNotNull(it.get(row.ENTITY_REF)),
                    LifecycleState.valueOf(checkNotNull(it.get(row.STATE))),
                )
            }
}
