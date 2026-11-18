package com.dynokore.gst.submission.application.ordering

import com.dynokore.gst.submission.application.queue.SubmissionQueue
import com.dynokore.gst.submission.domain.ordering.CreditNoteOrdering
import com.dynokore.gst.submission.domain.ordering.StateClass
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.tenancykernel.TenantId

/**
 * Releases the credit notes held on a document when that document is acknowledged (INV-REQ-709 behaviour 3). It runs
 * in the consumer transaction of the acknowledgement, so the release, the enqueue and the acknowledgement commit or
 * roll back together. Only a preceding document that is AT_IRAS releases; a document that will not go (NOT_GOING) or
 * is unknown is left to the sweep. A version the queue answers `Waiting` for stays READY (the waiting sweep releases
 * it); any exception propagates (fail closed).
 */
class HeldRelease(
    private val store: HoldStore,
    private val states: PrecedingStates,
    private val queue: SubmissionQueue,
) : AcknowledgedHook {
    override fun acknowledged(
        tenant: TenantId,
        docUuid: String,
    ) {
        val held = store.heldBy(tenant, docUuid)
        if (held.isEmpty()) return
        if (CreditNoteOrdering.classify(states.of(tenant, docUuid)) != StateClass.AT_IRAS) return
        for (version in held) {
            if (store.releaseHeld(tenant, version.docUuid, version.version) == 1) {
                queue.enqueue(version.docUuid, version.version, Lane.LIVE)
            }
        }
    }
}
