package com.dynokore.gst.access.adapters.persistence

import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/**
 * The tenant list of the full sync of AuthorityChanged (INV-T-9061): calls `access.replay_authority_tenants()`
 * (owned by the NOLOGIN `access_sys`, executable by `access_runtime` only) in its own transaction, outside any tenant
 * binding, as [JooqIdpSyncClaim] calls its function. The platform tenant is dropped by the replay source, not here.
 */
class JooqReplayTenants(
    private val dsl: DSLContext,
    private val tx: TransactionRunner,
) : () -> List<TenantId> {
    override fun invoke(): List<TenantId> =
        tx.inTransaction {
            val list = DSL.function(DSL.name("access", FUNCTION), SQLDataType.UUID)
            dsl.select(list).fetch { TenantId(checkNotNull(it.get(list))) }
        }

    companion object {
        const val FUNCTION = "replay_authority_tenants"
    }
}
