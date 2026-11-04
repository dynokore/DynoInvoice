package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ComplianceTenants
import com.dynokore.gst.submission.application.compliance.ComplianceTenantSource
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext

/**
 * [ComplianceTenantSource] over `submission.compliance_tenants` (INV-Q-16754 A): tenant ids only. Like
 * [JooqDueTenantSource] it reads under the platform binding of a job; the function reads across tenants as its definer.
 */
class JooqComplianceTenantSource(
    private val dsl: DSLContext,
    private val platform: PlatformTenantBinding,
    private val principal: WorkloadPrincipal,
    private val transactions: TransactionRunner,
) : ComplianceTenantSource {
    override fun tenants(): List<TenantId> =
        platform.bindPlatform(principal.value) {
            transactions.inTransaction {
                val tenants = ComplianceTenants.COMPLIANCE_TENANTS.call()
                dsl
                    .select(tenants.COMPLIANCE_TENANT)
                    .from(tenants)
                    .fetch()
                    .map { TenantId(checkNotNull(it.value1())) }
            }
        }
}
