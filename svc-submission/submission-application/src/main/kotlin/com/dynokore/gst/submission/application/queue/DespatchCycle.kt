package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.ordering.LifecycleState

/** The document version one turn of [DespatchCycle] despatched. */
data class DespatchedVersion(
    val docUuid: String,
    val version: Int,
)

/**
 * One turn of the polling loop (INV-Q-7266 A steps (2) to (5)): take an item, send it, apply the outcome. An item
 * whose row is `SENT` or `SENT_UNCONFIRMED` goes through [SentRecovery]; a `CANCELLED` row is a stale item, completed
 * and skipped (INV-REQ-573); any other state is a stop (the item stays
 * `CLAIMED`, never dropped silently). The thread that calls it at an interval is a later slice.
 */
class DespatchCycle(
    private val worker: SubmissionWorker,
    private val recovery: SentRecovery,
    private val transport: TransportClient,
    private val applier: AttemptOutcomeApplier,
) {
    /** @return false when no work was claimable. */
    fun runOnce(): Boolean = runOnceItem() != null

    /** @return the version this turn despatched, or null when no work was claimable. */
    fun runOnceItem(): DespatchedVersion? {
        var dispatch: Dispatch? = null
        while (dispatch == null) {
            val taken = worker.takeNext() ?: return null
            dispatch = dispatchOf(taken)
        }
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
        return DespatchedVersion(dispatch.docUuid, dispatch.version)
    }

    /** Null for a discarded item (a cancelled version): the turn takes the next item. */
    private fun dispatchOf(taken: SentResult): Dispatch? {
        val resolved =
            if (taken is SentResult.NotEligible && taken.state in RECOVERABLE) recovery.recover(taken) else taken
        return when (resolved) {
            is SentResult.Sent -> {
                resolved.dispatch
            }

            is SentResult.Discarded -> {
                null
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
