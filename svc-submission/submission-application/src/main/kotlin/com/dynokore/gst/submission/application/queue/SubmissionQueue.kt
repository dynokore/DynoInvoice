package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.ordering.InFlight
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.QueueEntry
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.util.UUID

/** The version is not in state `READY` (or does not exist), so nothing was enqueued and nothing was written. */
class VersionNotReady(
    val docUuid: String,
    val version: Int,
) : IllegalStateException("submission $docUuid version $version is not READY: nothing was enqueued")

/** The result of [SubmissionQueue.enqueue]. */
sealed interface EnqueueResult {
    /** The version moved `READY` to `QUEUED` and one item went to the work queue. */
    data object Queued : EnqueueResult

    /** Another version of the Doc UUID is in flight (INV-AC-610): nothing was written, the row stays `READY`. */
    data class Waiting(
        val blockingVersion: Int,
    ) : EnqueueResult
}

/** The result of [SubmissionQueue.releaseNext]. */
sealed interface ReleaseResult {
    data class Released(
        val version: Int,
    ) : ReleaseResult

    /** A version is in flight or no version is `READY`: nothing was written. */
    data object NothingToRelease : ReleaseResult
}

/**
 * The partial unique index `submission_in_flight_uq` refused a second in-flight version (INV-REQ-548 behaviour 4):
 * catalogue code [code] (SUB-008, terminal). The transaction rolls back and the version stays `READY`.
 */
class InFlightBreach(
    val docUuid: String,
    val version: Int,
    cause: Throwable? = null,
) : IllegalStateException(
        "${InFlight.SUB_008}: submission $docUuid version $version would be a second in-flight version",
        cause,
    ) {
    val code: String get() = InFlight.SUB_008
}

/**
 * Enqueues a `READY` version (INV-REQ-540 behaviour 1, INV-REQ-1156 behaviour 2, coordinator ruling INV-Q-7266 A): in
 * one tenant transaction the state row moves `READY` to `QUEUED` and one item goes to the service's `WorkQueue`. The
 * item holds the Doc UUID and the version only; the payload bytes stay in `submission.payload_bytes`. A crash before
 * the commit leaves the row `READY` with no item, so running the step again enqueues it once (INV-AC-2140).
 *
 * The rows of the Doc UUID are read `FOR UPDATE` in version order first, so two versions of one Doc UUID are
 * serialised: a version that finds another one in flight gets [EnqueueResult.Waiting] and writes nothing (INV-REQ-571
 * behaviour 2, INV-AC-610); [releaseNext] enqueues it once the other leaves flight.
 *
 * The caller's tenant is the one bound in the calling thread (ARC-21). This decides no hold.
 */
class SubmissionQueue(
    private val tenants: TenantContext,
    private val transactions: TransactionRunner,
    private val store: SubmissionStore,
    private val versions: InFlightStore,
    private val queue: WorkQueue,
) {
    /**
     * [lane] is the lane of the queue item; the default is the routine lane (LIVE).
     * @throws VersionNotReady when the version is not `READY`; nothing is written then.
     * @throws InFlightBreach when the unique index refuses the update (a bypass of the check above).
     */
    fun enqueue(
        docUuid: String,
        version: Int,
        lane: Lane = Lane.LIVE,
    ): EnqueueResult {
        val doc = UUID.fromString(docUuid).toString()
        require(version >= 1) { "version starts at 1" }
        return transactions.inTransaction {
            val rows = versions.lockVersions(tenants.current(), doc)
            if (rows.none { it.version == version && it.state == LifecycleState.READY }) {
                throw VersionNotReady(doc, version)
            }
            val blocking = rows.firstOrNull { it.version != version && InFlight.isInFlight(it.state) }
            if (blocking != null) EnqueueResult.Waiting(blocking.version) else queueLocked(doc, version, lane)
        }
    }

    /**
     * In one tenant transaction: when no version of the Doc UUID is in flight, enqueues the lowest `READY` version
     * exactly as [enqueue] does; otherwise, or when none is `READY`, writes nothing.
     */
    fun releaseNext(docUuid: String): ReleaseResult {
        val doc = UUID.fromString(docUuid).toString()
        return transactions.inTransaction {
            val rows = versions.lockVersions(tenants.current(), doc)
            val next = rows.firstOrNull { it.state == LifecycleState.READY }
            if (next == null || rows.any { InFlight.isInFlight(it.state) }) {
                ReleaseResult.NothingToRelease
            } else {
                queueLocked(doc, next.version, Lane.LIVE)
                ReleaseResult.Released(next.version)
            }
        }
    }

    /** Runs in the open transaction, after the rows of the Doc UUID are locked. */
    private fun queueLocked(
        doc: String,
        version: Int,
        lane: Lane,
    ): EnqueueResult {
        if (store.moveReadyToQueued(tenants.current(), doc, version) != 1) throw VersionNotReady(doc, version)
        queue.enqueue(QueueEntry(partitionKey = doc, lane = lane, payloadJson = itemPayload(doc, version)))
        return EnqueueResult.Queued
    }

    private companion object {
        /** The JSON object with exactly the members `docUuid` and `version` (a UUID text holds nothing to escape). */
        fun itemPayload(
            docUuid: String,
            version: Int,
        ): String = """{"docUuid":"$docUuid","version":$version}"""
    }
}
