package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.WindowPartState
import com.dynokore.gst.submission.application.completeness.StoredState
import com.dynokore.gst.submission.application.completeness.WindowPartStates
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.time.LocalDate

/**
 * [WindowPartStates] over `submission.window_part_state`: one statement, `insert ... on conflict do update ... where
 * stored state_version < new`, in one transaction of the bound tenant (INV-REQ-792, ruled case 4). FAILING is stored
 * as LATE, NOT_FAILING as CLOSED, OPEN as OPEN. Values are never logged.
 */
class JooqWindowPartStateStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : WindowPartStates {
    private val w = WindowPartState.WINDOW_PART_STATE

    @Suppress("LongParameterList")
    override fun record(
        tenant: TenantId,
        entityRef: String,
        period: String,
        from: LocalDate,
        to: LocalDate,
        state: StoredState,
        stateVersion: Long,
    ): Boolean {
        val column =
            when (state) {
                StoredState.FAILING -> "LATE"
                StoredState.NOT_FAILING -> "CLOSED"
                StoredState.OPEN -> "OPEN"
            }
        return runner.inTransaction {
            dsl
                .insertInto(w)
                .set(w.TENANT_ID, tenant.value)
                .set(w.ENTITY_REF, entityRef)
                .set(w.PERIOD_REF, period)
                .set(w.PART_FROM, from)
                .set(w.PART_TO, to)
                .set(w.STATE, column)
                .set(w.STATE_VERSION, stateVersion)
                .onConflict(w.TENANT_ID, w.ENTITY_REF, w.PERIOD_REF, w.PART_FROM, w.PART_TO)
                .doUpdate()
                .set(w.STATE, column)
                .set(w.STATE_VERSION, stateVersion)
                .where(w.STATE_VERSION.lt(stateVersion))
                .execute() == 1
        }
    }
}
