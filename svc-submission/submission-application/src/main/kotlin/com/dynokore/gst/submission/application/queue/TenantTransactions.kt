package com.dynokore.gst.submission.application.queue

import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner

/** The tenant binding, the current tenant and the transaction of the queue slices, with the service's own actor. */
class TenantTransactions(
    private val tenants: TenantContext,
    private val transactions: TransactionRunner,
    private val principal: WorkloadPrincipal,
) {
    /** Binds [tenant] with the service principal for [block]; the cross-tenant claim binds none (ARC-21). */
    fun <T> bound(
        tenant: TenantId,
        block: () -> T,
    ): T = tenants.bind(tenant, principal.value, block)

    fun <T> inTransaction(block: () -> T): T = transactions.inTransaction(block)

    fun current(): TenantId = tenants.current()
}
