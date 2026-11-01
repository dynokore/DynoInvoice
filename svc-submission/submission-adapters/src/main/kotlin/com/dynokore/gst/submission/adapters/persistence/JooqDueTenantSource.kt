package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.RetryDueTenants
import com.dynokore.gst.submission.application.queue.DueTenantSource
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.time.Instant
import java.time.ZoneOffset

/**
 * [DueTenantSource] over `submission.retry_due_tenants` (INV-Q-14325 A): tenant ids only. The jOOQ tenant guard needs
 * a binding for every statement, so the read runs under the platform binding of a job (PlatformTenantBinding), the
 * way the platform's own jobs do; the function reads across tenants as its definer.
 */
class JooqDueTenantSource(
    private val dsl: DSLContext,
    private val platform: PlatformTenantBinding,
    private val principal: WorkloadPrincipal,
    private val transactions: TransactionRunner,
) : DueTenantSource {
    override fun tenants(now: Instant): List<TenantId> =
        platform.bindPlatform(principal.value) {
            transactions.inTransaction {
                val due = RetryDueTenants.RETRY_DUE_TENANTS.call(now.atOffset(ZoneOffset.UTC))
                dsl
                    .select(due.DUE_TENANT)
                    .from(due)
                    .fetch()
                    .map { TenantId(checkNotNull(it.value1())) }
            }
        }
}
