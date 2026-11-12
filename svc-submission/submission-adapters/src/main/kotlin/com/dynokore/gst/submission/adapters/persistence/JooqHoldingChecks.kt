package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ReadinessCheck
import com.dynokore.gst.submission.application.readiness.HoldingChecks
import com.dynokore.gst.submission.application.readiness.StoredReadinessCheck
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import tools.jackson.databind.ObjectMapper

/**
 * [HoldingChecks] over `submission.readiness_check.outcome_values` (INV-REQ-72): the highest seq of the bound tenant,
 * entity and period whose `heldDocUuids` array holds an element equal to the whole [docUuid] text. The rows of the
 * period that record a `heldDocUuids` key are read newest first and the array is compared as parsed JSON strings;
 * the project's raw-SQL placement gate refuses the jsonb containment operator in main code (INV-T-4135).
 */
class JooqHoldingChecks(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : HoldingChecks {
    private val t = ReadinessCheck.READINESS_CHECK
    private val mapper = ObjectMapper()

    override fun holdingCheckId(
        entityRef: String,
        periodRef: String,
        docUuid: String,
    ): String? =
        dsl
            .select(t.SEQ, t.OUTCOME_VALUES)
            .from(t)
            .where(t.TENANT_ID.eq(tenants.current().value))
            .and(t.ENTITY_REF.eq(entityRef))
            .and(t.PERIOD_REF.eq(periodRef))
            .and(t.OUTCOME_VALUES.isNotNull)
            .orderBy(t.SEQ.desc())
            .fetch()
            .firstOrNull { r -> holds(r.get(t.OUTCOME_VALUES)?.data(), docUuid) }
            ?.let { StoredReadinessCheck.checkIdOf(checkNotNull(it.get(t.SEQ))) }

    private fun holds(
        outcome: String?,
        docUuid: String,
    ): Boolean {
        if (outcome == null) return false
        val held = mapper.readTree(outcome).get("heldDocUuids")
        return held != null && held.isArray && held.any { it.isString && it.stringValue() == docUuid }
    }
}
