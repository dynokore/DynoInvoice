package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.FeedControlState
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.WindowPartState
import com.dynokore.gst.submission.application.completeness.CompletenessRows
import com.dynokore.gst.submission.application.completeness.ControlIds
import com.dynokore.gst.submission.application.completeness.StoredRow
import com.dynokore.gst.submission.application.completeness.StoredState
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext

/**
 * [CompletenessRows] over `submission.feed_control_state` and `submission.window_part_state`, read for one tenant,
 * entity and period in one transaction (INV-REQ-792, ruled case 5). A window part LATE is FAILING, CLOSED is
 * NOT_FAILING, OPEN is OPEN; an unknown stored state fails closed with an exception rather than being dropped.
 */
class JooqCompletenessRows(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : CompletenessRows {
    private val f = FeedControlState.FEED_CONTROL_STATE
    private val w = WindowPartState.WINDOW_PART_STATE

    override fun rows(
        tenant: TenantId,
        entityRef: String,
        period: String,
    ): List<StoredRow> =
        runner.inTransaction {
            val feeds =
                dsl
                    .select(f.FEED, f.CONTROL, f.FAILING)
                    .from(f)
                    .where(f.TENANT_ID.eq(tenant.value))
                    .and(f.ENTITY_REF.eq(entityRef))
                    .and(f.PERIOD_REF.eq(period))
                    .fetch()
                    .map {
                        StoredRow(
                            ControlIds.feed(checkNotNull(it.get(f.FEED)), checkNotNull(it.get(f.CONTROL))),
                            if (it.get(f.FAILING) == true) StoredState.FAILING else StoredState.NOT_FAILING,
                        )
                    }
            val windows =
                dsl
                    .select(w.STATE)
                    .from(w)
                    .where(w.TENANT_ID.eq(tenant.value))
                    .and(w.ENTITY_REF.eq(entityRef))
                    .and(w.PERIOD_REF.eq(period))
                    .fetch()
                    .map { StoredRow(ControlIds.window(period), stateOf(it.get(w.STATE))) }
            feeds + windows
        }

    private fun stateOf(stored: String?): StoredState =
        when (stored) {
            "LATE" -> StoredState.FAILING
            "CLOSED" -> StoredState.NOT_FAILING
            "OPEN" -> StoredState.OPEN
            else -> error("unknown stored window part state")
        }
}
