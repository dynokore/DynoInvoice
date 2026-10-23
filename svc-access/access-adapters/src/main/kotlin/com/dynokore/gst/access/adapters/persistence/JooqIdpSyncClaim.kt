package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.application.idp.IdpSyncClaim
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/**
 * TPL-11. Calls the claim function `access.claim_idp_sync_tenants()` (owned by the NOLOGIN `access_sys`, executable by
 * `access_runtime` only) in its own transaction, under the platform binding the caller holds. The caller binds each
 * returned tenant before it runs the use case (ARC-21).
 */
class JooqIdpSyncClaim(
    private val dsl: DSLContext,
    private val tx: TransactionRunner,
) : IdpSyncClaim {
    override fun claimTenants(): List<TenantId> =
        tx.inTransaction {
            val claim = DSL.function(DSL.name("access", FUNCTION), SQLDataType.UUID)
            dsl.select(claim).fetch { TenantId(checkNotNull(it.get(claim))) }
        }

    companion object {
        const val FUNCTION = "claim_idp_sync_tenants"
    }
}
