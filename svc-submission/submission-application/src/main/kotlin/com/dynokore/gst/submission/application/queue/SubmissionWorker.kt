package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.WorkQueue

/**
 * The claim side of the queue (INV-Q-7266 A, INV-REQ-540 behaviour 2). Step (2): [WorkQueue.claimNext], which commits
 * as its own step; step (3): [SentTransaction.perform] with exactly `QUEUED` and `RETRY`. No transport client is
 * used here: the call (4) belongs to the attempts slice and runs after the commit of step (3).
 */
class SubmissionWorker(
    private val queue: WorkQueue,
    private val sent: SentTransaction,
) {
    /** Null when no work is claimable; otherwise the result of step (3), with the item still `CLAIMED`. */
    fun takeNext(): SentResult? {
        val item = queue.claimNext() ?: return null
        return sent.perform(item, ELIGIBLE)
    }

    private companion object {
        val ELIGIBLE: Set<LifecycleState> = setOf(LifecycleState.QUEUED, LifecycleState.RETRY)
    }
}
