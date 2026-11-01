package com.dynokore.gst.submission.application.queue

import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.QueueEntry
import com.dynokore.platform.outboxandqueue.WorkQueue
import java.time.Instant

/**
 * The due-scan of the retry delay (coordinator ruling INV-Q-7266 A, flow step (5) and "Retry delay"): in one tenant
 * transaction it clears `next_attempt_at` of every `RETRY` row whose value is not after `now` and enqueues one LIVE
 * item for each returned row, so a due version is enqueued exactly once; a second run with the same `now` finds
 * nothing. The tenant is the one bound in the calling thread (ARC-21).
 */
class RetryDueScan(
    private val scope: TenantTransactions,
    private val store: SubmissionStore,
    private val queue: WorkQueue,
) {
    /** @return the number of items enqueued. */
    fun run(now: Instant): Int =
        scope.inTransaction {
            val due = store.clearDueRetries(scope.current(), now)
            due.forEach { (docUuid, version) ->
                queue.enqueue(
                    QueueEntry(
                        partitionKey = docUuid,
                        lane = Lane.LIVE,
                        payloadJson = """{"docUuid":"$docUuid","version":$version}""",
                    ),
                )
            }
            due.size
        }
}
