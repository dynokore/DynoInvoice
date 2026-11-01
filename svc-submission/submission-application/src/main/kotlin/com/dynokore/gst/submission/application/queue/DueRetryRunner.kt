package com.dynokore.gst.submission.application.queue

import java.time.Instant

/**
 * Runs [RetryDueScan] for every tenant that holds a due row (INV-Q-14325 A). The tenant list is read outside any
 * tenant transaction; each tenant is then bound for its own scan. The thread that calls it is a later slice.
 */
class DueRetryRunner(
    private val source: DueTenantSource,
    private val scope: TenantTransactions,
    private val scan: RetryDueScan,
) {
    fun runOnce(now: Instant) {
        source.tenants(now).forEach { tenant ->
            scope.bound(tenant) { scan.run(now) }
        }
    }
}
