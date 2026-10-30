package com.dynokore.gst.submission.application.queue

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

/**
 * Enqueues a `READY` version (INV-REQ-540 behaviour 1, INV-REQ-1156 behaviour 2, coordinator ruling INV-Q-7266 A): in
 * one tenant transaction the state row moves `READY` to `QUEUED` and one item goes to the service's `WorkQueue`. The
 * item holds the Doc UUID and the version only; the payload bytes stay in `submission.payload_bytes`. A crash before
 * the commit leaves the row `READY` with no item, so running the step again enqueues it once (INV-AC-2140).
 *
 * The caller's tenant is the one bound in the calling thread (ARC-21). This enqueues every `READY` version it is asked
 * to and decides no hold.
 */
class SubmissionQueue(
    private val tenants: TenantContext,
    private val transactions: TransactionRunner,
    private val store: SubmissionStore,
    private val queue: WorkQueue,
) {
    /** @throws VersionNotReady when the version is not `READY`; nothing is written then. */
    fun enqueue(
        docUuid: String,
        version: Int,
    ) {
        val doc = UUID.fromString(docUuid).toString()
        require(version >= 1) { "version starts at 1" }
        transactions.inTransaction {
            if (store.moveReadyToQueued(tenants.current(), doc, version) != 1) throw VersionNotReady(doc, version)
            queue.enqueue(QueueEntry(partitionKey = doc, lane = Lane.LIVE, payloadJson = itemPayload(doc, version)))
        }
    }

    private companion object {
        /** The JSON object with exactly the members `docUuid` and `version` (a UUID text holds nothing to escape). */
        fun itemPayload(
            docUuid: String,
            version: Int,
        ): String = """{"docUuid":"$docUuid","version":$version}"""
    }
}
