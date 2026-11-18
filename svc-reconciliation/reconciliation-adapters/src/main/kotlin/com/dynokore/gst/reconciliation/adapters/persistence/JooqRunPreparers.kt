package com.dynokore.gst.reconciliation.adapters.persistence

import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.GST_RETURN
import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.RECONCILIATION_RUN
import com.dynokore.gst.reconciliation.application.workpaper.RunPreparers
import org.jooq.DSLContext
import java.util.UUID

/**
 * The preparer of a run (INV-T-24435, INV-AC-24434): the acting user ID that uploaded the return version the run
 * reconciled, never the free-text `preparer` column. Read only. Every statement names the tenant as well as the bound
 * tenant of the row-level security.
 */
class JooqRunPreparers(
    private val dsl: DSLContext,
) : RunPreparers {
    override fun preparerOf(
        tenant: String,
        runId: String,
    ): String? {
        val run = runCatching { UUID.fromString(runId) }.getOrNull() ?: return null
        val tenantId = UUID.fromString(tenant)
        return dsl
            .select(GST_RETURN.UPLOADED_BY)
            .from(RECONCILIATION_RUN)
            .join(GST_RETURN)
            .on(GST_RETURN.TENANT_ID.eq(RECONCILIATION_RUN.TENANT_ID))
            .and(GST_RETURN.ENTITY_REF.eq(RECONCILIATION_RUN.ENTITY_REF))
            .and(GST_RETURN.PERIOD_REF.eq(RECONCILIATION_RUN.PERIOD_REF))
            .and(GST_RETURN.VERSION.eq(RECONCILIATION_RUN.RETURN_VERSION))
            .where(RECONCILIATION_RUN.TENANT_ID.eq(tenantId))
            .and(GST_RETURN.TENANT_ID.eq(tenantId))
            .and(RECONCILIATION_RUN.RUN_ID.eq(run))
            .fetchOne(GST_RETURN.UPLOADED_BY)
    }
}
