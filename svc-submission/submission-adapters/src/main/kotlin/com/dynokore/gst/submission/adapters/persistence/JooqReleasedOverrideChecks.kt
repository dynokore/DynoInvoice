package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.FilingOverride
import com.dynokore.gst.submission.application.readiness.ReleasedOverrideChecks
import com.dynokore.gst.submission.domain.gate.OverrideStatus
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext

/** [ReleasedOverrideChecks] over the RELEASED rows of the bound tenant, entity and period (INV-REQ-72). */
class JooqReleasedOverrideChecks(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : ReleasedOverrideChecks {
    private val t = FilingOverride.FILING_OVERRIDE

    override fun releasedCheckIds(
        entityRef: String,
        periodRef: String,
    ): Set<String> =
        dsl
            .select(t.CHECK_ID)
            .from(t)
            .where(t.TENANT_ID.eq(tenants.current().value))
            .and(t.ENTITY_REF.eq(entityRef))
            .and(t.PERIOD_REF.eq(periodRef))
            .and(t.STATUS.eq(OverrideStatus.RELEASED.name))
            .fetch(t.CHECK_ID)
            .filterNotNull()
            .toSet()
}
