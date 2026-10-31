package com.dynokore.gst.evidence.adapters.http

import com.dynokore.gst.evidence.application.retention.MaxRetention
import com.dynokore.platform.outboxandqueue.TenantScope
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * The one reading of the installation's longest VP-43 retention (INV-REQ-3767, INV-Q-12043): the figure of
 * [MaxRetention] read in a platform transaction under the service's own workload principal, so no tenant is bound and
 * none is read. `getMaxEvidenceRetention` and the digest consumer's retention bean both call it.
 */
class PlatformMaxRetention(
    private val maxRetention: MaxRetention,
    private val scope: TenantScope,
) {
    fun <T> read(block: (MaxRetention) -> T): T {
        val platform = checkNotNull(scope.platform) { "the platform binding is missing" }
        return platform.bindPlatform(scope.workloadPrincipal) {
            scope.transactions.inTransaction { block(maxRetention) }
        }
    }

    /**
     * [read] in a transaction of its own on a separate thread: a caller that already holds a tenant transaction (the
     * digest consumer) cannot open the platform one inside it (INV-Q-3997). A failure is rethrown on the caller's
     * thread. The wait is bounded by [READ_TIMEOUT]; on expiry the worker is interrupted and the call throws, so
     * the caller fails closed (INV-Q-13964).
     */
    fun <T> readIndependently(block: (MaxRetention) -> T): T {
        val outcome = AtomicReference<Result<T>>()
        val worker = Thread.ofVirtual().name("max-retention-read").start { outcome.set(runCatching { read(block) }) }
        if (!worker.join(READ_TIMEOUT)) {
            worker.interrupt()
            throw TimeoutException("the longest retention was not read within $READ_TIMEOUT")
        }
        return outcome.get().getOrThrow()
    }

    companion object {
        /** The `x-timeout` of `getMaxEvidenceRetention` in the pinned contract bundle (2000 ms). */
        val READ_TIMEOUT: Duration = Duration.ofMillis(2000)
    }
}
