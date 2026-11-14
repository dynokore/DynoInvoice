package com.dynokore.gst.consoleBff.adapters.deferral

import com.dynokore.gst.consoleBff.adapters.persistence.jooq.tables.references.EXCEPTION
import com.dynokore.gst.consoleBff.application.deferral.DeferralTargets
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import java.util.UUID

/**
 * Existence of a deferral target over `console_bff.exception`. The port takes no tenant: the tenant is the caller's
 * signed database binding, which forced row level security enforces and every statement here also names (defence in
 * depth, NIST SP 800-53 AC-3). A target is open only while a row of the entity is in state OPEN. Read only.
 * Inference (INV-Q-21112): a DOCUMENT ref is the doc_uuid; a GROUP ref is the whole key `owner/code`.
 */
class JooqDeferralTargets(
    private val dsl: DSLContext,
) : DeferralTargets {
    private val boundTenant: Field<UUID?> =
        DSL.field(DSL.select(DSL.function(DSL.name("console_bff", "bound_tenant"), UUID::class.java)))

    override fun openDocument(
        entityRef: String,
        docUuid: String,
    ): Boolean = openRow(entityRef, EXCEPTION.DOC_UUID.eq(docUuid))

    override fun openGroup(
        entityRef: String,
        groupKey: String,
    ): Boolean =
        openRow(
            entityRef,
            EXCEPTION.REMEDIATION_OWNER
                .concat(DSL.inline("/"))
                .concat(EXCEPTION.CODE)
                .eq(groupKey),
        )

    private fun openRow(
        entityRef: String,
        target: Condition,
    ): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(EXCEPTION)
                .where(EXCEPTION.TENANT_ID.eq(boundTenant))
                .and(EXCEPTION.ENTITY_REF.eq(entityRef))
                .and(target)
                .and(EXCEPTION.STATE.eq(OPEN)),
        )

    private companion object {
        const val OPEN = "OPEN"
    }
}
