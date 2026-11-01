package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.WaitingTenants
import com.dynokore.gst.submission.application.queue.WaitingTenantSource
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext

/**
 * [WaitingTenantSource] over `submission.waiting_tenants` (INV-Q-15333 A): tenant ids only. Like
 * [JooqDueTenantSource] it reads under the platform binding of a job; the function reads across tenants as its definer.
 */
class JooqWaitingTenantSource(
    private val dsl: DSLContext,
    private val platform: PlatformTenantBinding,
    private val principal: WorkloadPrincipal,
    private val transactions: TransactionRunner,
) : WaitingTenantSource {
    override fun tenants(): List<TenantId> =
        platform.bindPlatform(principal.value) {
            transactions.inTransaction {
                val waiting = WaitingTenants.WAITING_TENANTS.call()
                dsl
                    .select(waiting.WAITING_TENANT)
                    .from(waiting)
                    .fetch()
                    .map { TenantId(checkNotNull(it.value1())) }
            }
        }
}
