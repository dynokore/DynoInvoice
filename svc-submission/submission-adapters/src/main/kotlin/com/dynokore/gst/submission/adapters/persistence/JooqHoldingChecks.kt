package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ReadinessCheck
import com.dynokore.gst.submission.application.readiness.HoldingChecks
import com.dynokore.gst.submission.application.readiness.StoredReadinessCheck
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/**
 * [HoldingChecks] over `submission.readiness_check.outcome_values` (INV-REQ-72): the highest seq of the bound tenant,
 * entity and period whose `heldDocUuids` array holds an element equal to the whole [docUuid] text. The predicate runs
 * in the database through jOOQ's typed `jsonbExists` (a jsonpath equality, bound as a parameter), so one document costs
 * one aggregate row, never the history. Plain SQL is refused by the placement gate and by tenancy-jooq (INV-T-4135).
 */
class JooqHoldingChecks(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : HoldingChecks {
    private val t = ReadinessCheck.READINESS_CHECK

    override fun holdingCheckId(
        entityRef: String,
        periodRef: String,
        docUuid: String,
    ): String? =
        dsl
            .select(DSL.max(t.SEQ))
            .from(t)
            .where(t.TENANT_ID.eq(tenants.current().value))
            .and(t.ENTITY_REF.eq(entityRef))
            .and(t.PERIOD_REF.eq(periodRef))
            .and(DSL.jsonbExists(t.OUTCOME_VALUES, DSL.`val`(pathOf(docUuid), SQLDataType.VARCHAR)))
            .fetchOne()
            ?.get(0, Long::class.javaObjectType)
            ?.let { StoredReadinessCheck.checkIdOf(it) }

    /** `$.heldDocUuids[*] ? (@ == "<uuid>")` with the uuid as a jsonpath string literal (backslash and quote escaped). */
    private fun pathOf(docUuid: String): String {
        val literal = docUuid.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\$.heldDocUuids[*] ? (@ == \"$literal\")"
    }
}
