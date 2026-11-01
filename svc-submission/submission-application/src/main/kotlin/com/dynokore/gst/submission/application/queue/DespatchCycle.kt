package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.ordering.LifecycleState

/**
 * One turn of the polling loop (INV-Q-7266 A steps (2) to (5)): take an item, send it, apply the outcome. An item
 * whose row is `SENT` or `SENT_UNCONFIRMED` goes through [SentRecovery]; any other state is a stop (the item stays
 * `CLAIMED`, never dropped silently). The thread that calls it at an interval is a later slice.
 */
class DespatchCycle(
    private val worker: SubmissionWorker,
    private val recovery: SentRecovery,
    private val transport: TransportClient,
    private val applier: AttemptOutcomeApplier,
) {
    /** @return false when no work was claimable. */
    fun runOnce(): Boolean {
        val taken = worker.takeNext() ?: return false
        val dispatch = dispatchOf(taken)
        val result =
            transport.submit(
                TransportRequest(
                    tenant = dispatch.item.tenant,
                    docUuid = dispatch.docUuid,
                    version = dispatch.version,
                    entityRef = dispatch.entityRef,
                    payload = dispatch.requestBytes,
                    idempotencyKey = dispatch.correlationId,
                ),
            )
        applier.apply(dispatch, result)
        return true
    }

    private fun dispatchOf(taken: SentResult): Dispatch {
        val resolved =
            if (taken is SentResult.NotEligible && taken.state in RECOVERABLE) recovery.recover(taken) else taken
        return when (resolved) {
            is SentResult.Sent -> {
                resolved.dispatch
            }

            is SentResult.NotEligible -> {
                error(
                    "item ${resolved.item.seq} holds a version in state ${resolved.state}: stop",
                )
            }
        }
    }

    private companion object {
        val RECOVERABLE = setOf(LifecycleState.SENT, LifecycleState.SENT_UNCONFIRMED)
    }
}
