package com.dynokore.gst.masterdata.application.gapreport

import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Duration
import java.time.Instant
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

/**
 * Finalises FAILED every RUNNING record older than [maxRun] (INV-Q-17291): a gap report, a dry run or an import whose
 * worker died. A younger record is left alone. A job runs under no tenant, so the tenants come from [source] and each
 * tenant's records are updated under that tenant's own binding, in its own transaction, so every update stays under the
 * tenant's row-level security (INV-Q-18051 A). A failure for one tenant is logged with the tenant id and the others go
 * on; the call then throws when any tenant failed, so the job run is recorded failed. Returns how many were finalised.
 */
@Suppress("LongParameterList") // the ports of one sweep: tenants, clock, setting and the three stores
class SweepStaleRecords(
    private val tx: TransactionRunner,
    private val clock: Clock,
    private val maxRun: Duration,
    private val source: StaleRecordTenantSource,
    private val tenants: TenantContext,
    private val actor: String,
    private val sweeps: List<(Instant) -> Int>,
) {
    fun run(): Int {
        val before = clock.instant().minus(maxRun)
        var finalised = 0
        val failed = mutableListOf<TenantId>()
        source.tenants(before).forEach { tenant ->
            try {
                finalised += tenants.bind(tenant, actor) { tx.inTransaction { sweeps.sumOf { it(before) } } }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                LOG.log(System.Logger.Level.ERROR, "long-command sweep failed for tenant ${tenant.value}", e)
                failed += tenant
            }
        }
        check(failed.isEmpty()) { "the long-command sweep failed for ${failed.size} tenant(s)" }
        return finalised
    }

    private companion object {
        private val LOG = System.getLogger(SweepStaleRecords::class.java.name)
    }
}
