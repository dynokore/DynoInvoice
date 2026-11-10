package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.ConnectionExpiryTenants
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext

/** The tenants that have a connection credential with an expiry: the input of the expiry-check job. */
fun interface ExpiryTenantSource {
    fun tenants(): List<TenantId>
}

/**
 * [ExpiryTenantSource] over `masterdata.connection_expiry_tenants` (modelled on [JooqStaleRecordTenantSource]):
 * tenant ids only. It reads under the platform binding of the job; the function reads across tenants as its definer.
 */
class JooqExpiryTenantSource(
    private val dsl: DSLContext,
    private val platform: PlatformTenantBinding,
    private val principal: WorkloadPrincipal,
    private val transactions: TransactionRunner,
) : ExpiryTenantSource {
    override fun tenants(): List<TenantId> =
        platform.bindPlatform(principal.value) {
            transactions.inTransaction {
                val rows = ConnectionExpiryTenants.CONNECTION_EXPIRY_TENANTS.call()
                dsl
                    .select(rows.EXPIRY_TENANT)
                    .from(rows)
                    .fetch()
                    .map { TenantId(checkNotNull(it.value1())) }
            }
        }
}
