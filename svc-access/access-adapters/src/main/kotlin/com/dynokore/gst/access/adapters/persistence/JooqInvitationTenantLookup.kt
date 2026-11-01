package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.application.invitation.InvitationTenantLookup
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/**
 * TPL-11. Calls `access.resolve_invitation_tenant(invitation_ref)` (owned by the NOLOGIN `access_sys`, executable by
 * `access_runtime` only) in a read transaction, under the platform binding the caller holds (INV-T-8227). The table
 * is never read with row-level security off.
 */
class JooqInvitationTenantLookup(
    private val dsl: DSLContext,
    private val tx: TransactionRunner,
) : InvitationTenantLookup {
    override fun tenantOf(invitationRef: String): TenantId? =
        tx.inTransaction {
            val resolve =
                DSL.function(DSL.name("access", FUNCTION), SQLDataType.UUID, DSL.`val`(invitationRef, SQLDataType.CLOB))
            dsl
                .select(resolve)
                .fetchOne()
                ?.get(resolve)
                ?.let { TenantId(it) }
        }

    companion object {
        const val FUNCTION = "resolve_invitation_tenant"
    }
}
