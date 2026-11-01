package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.transitions.Event
import com.dynokore.gst.submission.domain.transitions.Signal
import com.dynokore.gst.submission.domain.transitions.Transition
import com.dynokore.gst.submission.domain.transitions.VersionRecord
import com.dynokore.gst.submission.domain.transitions.VersionTransitions
import com.dynokore.platform.outboxandqueue.WorkQueue
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * Restart recovery of an item re-claimed by [SubmissionWorker.takeNext] whose row is `SENT` or `SENT_UNCONFIRMED`
 * (INV-REQ-540 behaviour 4, INV-Q-14319 A). Two tenant transactions: (a) `SENT` without acknowledgement becomes
 * `SENT_UNCONFIRMED` by the restart tick of [VersionTransitions] (skipped when the row is already
 * `SENT_UNCONFIRMED`); (b) [SentTransaction.perform] moves it back to `SENT` with a new attempt. A row in any other
 * state, or `SENT` with an acknowledgement, is returned unchanged with nothing written; the caller reports it.
 */
class SentRecovery(
    private val clock: Clock,
    private val scope: TenantTransactions,
    private val store: SubmissionStore,
    private val queue: WorkQueue,
    private val sent: SentTransaction,
) {
    fun recover(notEligible: SentResult.NotEligible): SentResult {
        val item = notEligible.item
        val refused = scope.bound(item.tenant) { scope.inTransaction { markUnconfirmed(notEligible) } }
        if (refused != null) return refused
        return sent.perform(item, setOf(LifecycleState.SENT_UNCONFIRMED))
    }

    /** Transaction (a); a non-null result is the state to hand back with nothing written. */
    private fun markUnconfirmed(notEligible: SentResult.NotEligible): SentResult.NotEligible? {
        val item = notEligible.item
        val tenant = scope.current()
        val (doc, version) = ItemPayload.parse(queue.payloadOf(item))
        val row =
            checkNotNull(
                store.lockForUpdate(tenant, doc, version),
            ) { "submission $doc version $version does not exist" }
        return when {
            row.state == LifecycleState.SENT_UNCONFIRMED -> {
                null
            }

            row.state != LifecycleState.SENT || row.acknowledgementId != null -> {
                SentResult.NotEligible(row.state, item)
            }

            else -> {
                val now = clock.instant()
                val later = now.plus(1, ChronoUnit.MILLIS)
                val decision =
                    VersionTransitions.decide(
                        listOf(VersionRecord(version, LifecycleState.SENT)),
                        Event.TimerTick(version, now, now, later, later, later, 0, false, false),
                    )
                check(
                    decision.transitions ==
                        listOf(Transition(version, LifecycleState.SENT, LifecycleState.SENT_UNCONFIRMED)) &&
                        decision.signals == listOf(Signal.Resend(version)),
                ) { "restart tick of $doc version $version: unexpected decision $decision" }
                store.setState(tenant, doc, version, LifecycleState.SENT_UNCONFIRMED)
                null
            }
        }
    }
}
