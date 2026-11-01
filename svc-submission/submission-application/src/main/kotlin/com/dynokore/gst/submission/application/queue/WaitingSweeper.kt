package com.dynokore.gst.submission.application.queue

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Duration

/** The tenants that may hold a waiting `READY` version, read outside any tenant transaction. */
fun interface WaitingTenantSource {
    fun tenants(): List<TenantId>
}

/**
 * The safety net of the wait (INV-REQ-571 behaviour 2, INV-AC-610): a `READY` version whose older version left flight
 * without the outcome step's [SubmissionQueue.releaseNext] call (a crash between commit and call) is released here.
 * Per tenant it selects up to [BATCH] Doc UUIDs that have a `READY` version and no version in flight and calls
 * `releaseNext` for each; a failure for one Doc UUID is logged and the others go on.
 */
class WaitingSweeper(
    private val source: WaitingTenantSource,
    private val scope: TenantTransactions,
    private val versions: InFlightStore,
    private val queue: SubmissionQueue,
) {
    /** @return the number of versions released. */
    fun sweep(): Int =
        source.tenants().sumOf { tenant ->
            scope.bound(tenant) {
                val docs = scope.inTransaction { versions.waitingDocUuids(scope.current(), BATCH) }
                docs.count { doc -> release(doc) }
            }
        }

    private fun release(doc: String): Boolean =
        try {
            queue.releaseNext(doc) is ReleaseResult.Released
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            LOG.log(System.Logger.Level.ERROR, "waiting sweep: releaseNext of $doc failed", e)
            false
        }

    companion object {
        /** The most Doc UUIDs one sweep takes per tenant. */
        const val BATCH = 100

        /** The interval between two sweeps; the one constant the scheduler registration reads. */
        val INTERVAL: Duration = Duration.ofSeconds(60)

        private val LOG = System.getLogger(WaitingSweeper::class.java.name)
    }
}
