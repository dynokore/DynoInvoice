package com.dynokore.gst.aggregation.adapters.persistence

import com.dynokore.gst.aggregation.adapters.events.WindowStateVersions
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.WindowStateVersion.Companion.WINDOW_STATE_VERSION
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.time.LocalDate

/**
 * The counter of `aggregation.window_state_version`: one upsert statement that inserts 1 or adds 1 and returns the new
 * value. It runs through [TransactionRunner.inTransaction], so it joins the open transaction of the close: a rollback
 * of the close rolls the counter back. The row is tenant data under forced row level security.
 */
class JooqWindowStateVersions(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : WindowStateVersions {
    override fun next(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
        from: LocalDate,
        to: LocalDate,
    ): Long =
        runner.inTransaction {
            val version = WINDOW_STATE_VERSION.STATE_VERSION
            checkNotNull(
                dsl
                    .insertInto(WINDOW_STATE_VERSION)
                    .set(WINDOW_STATE_VERSION.TENANT_ID, tenant.value)
                    .set(WINDOW_STATE_VERSION.ENTITY_REF, entityRef)
                    .set(WINDOW_STATE_VERSION.PERIOD_REF, periodRef)
                    .set(WINDOW_STATE_VERSION.PART_FROM, from)
                    .set(WINDOW_STATE_VERSION.PART_TO, to)
                    .set(version, 1L)
                    .onConflict(
                        WINDOW_STATE_VERSION.TENANT_ID,
                        WINDOW_STATE_VERSION.ENTITY_REF,
                        WINDOW_STATE_VERSION.PERIOD_REF,
                        WINDOW_STATE_VERSION.PART_FROM,
                        WINDOW_STATE_VERSION.PART_TO,
                    ).doUpdate()
                    .set(version, version.plus(1L))
                    .returning(version)
                    .fetchOne()
                    ?.get(version),
            ) { "the counter statement returned no row" }
        }
}
