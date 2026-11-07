package com.dynokore.gst.masterdata.application.gapreport

import com.dynokore.platform.tenancykernel.TenantContext
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** What a long command did within its wait: finished with a [value], or is still running (API-17, INV-Q-17291). */
sealed interface LongOutcome<out T> {
    data class Finished<T>(
        val value: T,
    ) : LongOutcome<T>

    data object StillRunning : LongOutcome<Nothing>
}

/**
 * Runs the work of a long command on a bounded executor under the caller's tenant and actor, and waits up to
 * [timeout] (the operation's `x-timeout`). A work that outlasts the wait keeps running; its record is the one place
 * the caller reads the result. The work must finalise its own record, also when it fails (INV-Q-17291).
 */
class LongRunner(
    private val tenants: TenantContext,
    private val executor: ExecutorService,
    private val timeout: Duration,
) {
    fun <T> run(work: () -> T): LongOutcome<T> {
        val tenant = tenants.current()
        val actor = tenants.currentActor() ?: error("a long command needs an actor")
        val future: Future<T> =
            try {
                executor.submit<T> { tenants.bind(tenant, actor, work) }
            } catch (e: RejectedExecutionException) {
                throw IllegalStateException("the long-command executor is full", e)
            }
        return try {
            LongOutcome.Finished(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS))
        } catch (_: TimeoutException) {
            LongOutcome.StillRunning
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }
}
