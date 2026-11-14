package com.dynokore.gst.notification.adapters.tenants

import com.dynokore.gst.notification.application.alerting.PerTenantTransaction
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId

/**
 * One transaction per tenant under that tenant's own binding (forced row-level security; OWASP ASVS V4). The consumer's
 * platform transaction stays open around it, so [TenantScope.transactions] clears the open-transaction marker for the
 * block and the inner transaction takes its own connection (INV-Q-5609 (b), INV-Q-3997). The acting identity is the
 * workload principal; no role is checked (INV-Q-10027).
 */
class TenantScopeTransactions(
    private val scope: TenantScope,
) : PerTenantTransaction {
    override fun <T> run(
        tenant: TenantId,
        block: () -> T,
    ): T =
        scope.bindWorkload(tenant) {
            scope.transactions.outsideOpenTransaction { scope.transactions.inTransaction { block() } }
        }
}
