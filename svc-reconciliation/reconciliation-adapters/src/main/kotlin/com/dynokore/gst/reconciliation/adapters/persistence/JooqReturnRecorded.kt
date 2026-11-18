package com.dynokore.gst.reconciliation.adapters.persistence

import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.GST_RETURN
import com.dynokore.gst.reconciliation.application.run.ReturnRecorded
import org.jooq.DSLContext
import java.util.UUID

/**
 * Is a return recorded for the period of the tenant (INV-T-24421, INV-REQ-657): one `exists` statement over
 * `reconciliation.gst_return` (columns `tenant_id`, `entity_ref`, `period_ref`). The tenant is named in the WHERE as
 * well as bound for the row-level security. Nothing is cached; a failure propagates.
 */
class JooqReturnRecorded(
    private val dsl: DSLContext,
) : ReturnRecorded {
    override fun exists(
        tenant: String,
        entityRef: String,
        periodRef: String,
    ): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(GST_RETURN)
                .where(GST_RETURN.TENANT_ID.eq(UUID.fromString(tenant)))
                .and(GST_RETURN.ENTITY_REF.eq(entityRef))
                .and(GST_RETURN.PERIOD_REF.eq(periodRef)),
        )
}
