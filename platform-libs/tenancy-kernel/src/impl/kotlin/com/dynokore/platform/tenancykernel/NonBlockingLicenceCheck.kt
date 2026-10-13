package com.dynokore.platform.tenancykernel

import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Enforces FR-G.12 structurally: wraps a real [LicenceCheck] (the SaaS
 * entitlement source, or a private installation's signed licence file) so
 * that *no* implementation underneath it can block or throw past a caller of
 * this port. A timeout, or any exception the delegate raises, becomes
 * [LicenceStatus.UNKNOWN] — never propagated.
 *
 * This is deliberately a decorator rather than a rule the real implementation
 * must remember to follow: a new `LicenceCheck` (a slow HTTP call to
 * control-plane-svc, a corrupt or missing licence file) gets the guarantee for
 * free by construction, which is the point of a *kernel* module owning it.
 *
 * The bounded executor is daemon-threaded and does not interrupt a delegate
 * call that has timed out — [status] returns [LicenceStatus.UNKNOWN]
 * immediately either way, and the abandoned call is left to finish or fail on
 * its own thread rather than risk an interrupt corrupting a shared client.
 */
class NonBlockingLicenceCheck(
    private val delegate: LicenceCheck,
    private val timeout: Duration = Duration.ofMillis(200),
    private val executor: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "licence-check").apply { isDaemon = true }
        },
) : LicenceCheck {
    override fun status(tenant: TenantId): LicenceStatus =
        try {
            executor
                .submit(Callable { delegate.status(tenant) })
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            LicenceStatus.UNKNOWN
        } catch (_: Exception) {
            LicenceStatus.UNKNOWN
        }
}
