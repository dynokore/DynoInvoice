package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.MeasuredTenants
import com.dynokore.gst.submission.application.offboarding.MeasuredTenantSource
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext

/**
 * [MeasuredTenantSource] over `submission.measured_tenants()` (INV-REQ-956): tenant ids only, those with a register
 * entry or an entity node. Like [JooqComplianceTenantSource] it reads under the platform binding of a job; the
 * function reads across tenants as its definer.
 */
class JooqMeasuredTenantSource(
    private val dsl: DSLContext,
    private val platform: PlatformTenantBinding,
    private val principal: WorkloadPrincipal,
    private val transactions: TransactionRunner,
) : MeasuredTenantSource {
    override fun tenants(): List<TenantId> =
        platform.bindPlatform(principal.value) {
            transactions.inTransaction {
                val tenants = MeasuredTenants.MEASURED_TENANTS.call()
                dsl
                    .select(tenants.MEASURED_TENANT)
                    .from(tenants)
                    .fetch()
                    .map { TenantId(checkNotNull(it.value1())) }
            }
        }
}
