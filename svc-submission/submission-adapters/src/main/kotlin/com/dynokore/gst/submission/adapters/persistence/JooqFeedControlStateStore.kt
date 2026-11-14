package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.FeedControlState
import com.dynokore.gst.submission.application.completeness.FeedControlStates
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext

/**
 * [FeedControlStates] over `submission.feed_control_state`: one statement, `insert ... on conflict do update ... where
 * stored state_version < new`, in one transaction of the bound tenant (INV-REQ-792, ruled case 4). A redelivered or
 * older event changes no row; forced row level security applies. Values are never logged.
 */
class JooqFeedControlStateStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : FeedControlStates {
    private val f = FeedControlState.FEED_CONTROL_STATE

    @Suppress("LongParameterList")
    override fun record(
        tenant: TenantId,
        feed: String,
        entityRef: String,
        period: String,
        control: String,
        failing: Boolean,
        stateVersion: Long,
    ): Boolean =
        runner.inTransaction {
            dsl
                .insertInto(f)
                .set(f.TENANT_ID, tenant.value)
                .set(f.FEED, feed)
                .set(f.ENTITY_REF, entityRef)
                .set(f.PERIOD_REF, period)
                .set(f.CONTROL, control)
                .set(f.FAILING, failing)
                .set(f.STATE_VERSION, stateVersion)
                .onConflict(f.TENANT_ID, f.FEED, f.ENTITY_REF, f.PERIOD_REF, f.CONTROL)
                .doUpdate()
                .set(f.FAILING, failing)
                .set(f.STATE_VERSION, stateVersion)
                .where(f.STATE_VERSION.lt(stateVersion))
                .execute() == 1
        }
}
