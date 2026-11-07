package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.StaleRecordTenants
import com.dynokore.gst.masterdata.application.gapreport.StaleRecordTenantSource
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.time.Instant
import java.time.ZoneOffset

/**
 * [StaleRecordTenantSource] over `masterdata.stale_record_tenants` (INV-Q-18051 A): tenant ids only. It reads under the
 * platform binding of the job; the function reads across tenants as its definer.
 */
class JooqStaleRecordTenantSource(
    private val dsl: DSLContext,
    private val platform: PlatformTenantBinding,
    private val principal: WorkloadPrincipal,
    private val transactions: TransactionRunner,
) : StaleRecordTenantSource {
    override fun tenants(before: Instant): List<TenantId> =
        platform.bindPlatform(principal.value) {
            transactions.inTransaction {
                val stale = StaleRecordTenants.STALE_RECORD_TENANTS.call(before.atOffset(ZoneOffset.UTC))
                dsl
                    .select(stale.STALE_TENANT)
                    .from(stale)
                    .fetch()
                    .map { TenantId(checkNotNull(it.value1())) }
            }
        }
}
