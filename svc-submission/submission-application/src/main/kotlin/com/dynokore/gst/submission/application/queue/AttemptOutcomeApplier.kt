package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.ordering.InFlight
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.retry.BackoffPolicy
import com.dynokore.gst.submission.domain.retry.FailureInput
import com.dynokore.gst.submission.domain.retry.FailureResult
import com.dynokore.gst.submission.domain.retry.RetryPlanner
import com.dynokore.gst.submission.domain.transitions.Event
import com.dynokore.gst.submission.domain.transitions.RejectionSource
import com.dynokore.gst.submission.domain.transitions.Transition
import com.dynokore.gst.submission.domain.transitions.VersionRecord
import com.dynokore.gst.submission.domain.transitions.VersionTransitions
import com.dynokore.platform.outboxandqueue.WorkQueue
import java.time.Clock

/**
 * Step (5) of the claim flow (INV-Q-7266 A, INV-REQ-540 behaviour 3): in a new tenant transaction on the row locked
 * `FOR UPDATE` it records the response of the attempt and applies the outcome; in every case it completes the queue
 * item in the same transaction, so a failure of any write leaves response, state and item unchanged. The same pair
 * (Doc UUID, version) is re-sent by a retry and no identifier is minted (INV-REQ-541). A state or decision outside
 * the expected one throws and rolls back: a stop for the caller to report. The failure code of a rejected or
 * transiently failed attempt is recorded where the outcome is classified, and a REJECTED or DEAD_LETTERED version is
 * reported through [FailureReporting] after its state write, in the same transaction; an accepted or retried outcome
 * reports nothing.
 */
@Suppress("LongParameterList")
class AttemptOutcomeApplier(
    private val clock: Clock,
    private val boundaries: RetryBoundarySource,
    private val jitter: JitterSource,
    private val scope: TenantTransactions,
    private val store: SubmissionStore,
    private val queue: WorkQueue,
    private val submissions: SubmissionQueue,
    private val failures: FailureReporting,
) {
    fun apply(
        dispatch: Dispatch,
        result: TransportResult,
    ) {
        scope.bound(dispatch.item.tenant) {
            val left = scope.inTransaction { applyBound(dispatch, result) }
            if (left) releaseWaiting(dispatch)
        }
    }

    /**
     * After the commit (INV-REQ-571 behaviour 2): a version left flight, so the next `READY` version of the Doc UUID
     * may go. A failure is logged and never undoes the outcome; the [WaitingSweeper] picks the Doc UUID up later.
     */
    private fun releaseWaiting(dispatch: Dispatch) {
        try {
            submissions.releaseNext(dispatch.docUuid)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            LOG.log(System.Logger.Level.ERROR, "releaseNext of ${dispatch.docUuid} after its outcome failed", e)
        }
    }

    /** @return true when the version left the four in-flight states. */
    private fun applyBound(
        dispatch: Dispatch,
        result: TransportResult,
    ): Boolean {
        val tenant = scope.current()
        val (doc, version) = dispatch.docUuid to dispatch.version
        val row =
            checkNotNull(
                store.lockForUpdate(tenant, doc, version),
            ) { "submission $doc version $version does not exist" }
        check(row.state == LifecycleState.SENT) {
            "the outcome of $doc version $version arrived in state ${row.state}, expected SENT"
        }
        val now = clock.instant()
        store.recordResponse(tenant, AttemptResponse(doc, version, dispatch.attemptNumber, result.responseBytes, now))
        when (result) {
            is TransportResult.Accepted -> accepted(dispatch, now)
            is TransportResult.Rejected -> rejected(dispatch, result)
            is TransportResult.TransientFailure -> transientFailure(dispatch, row, result.code)
        }
        queue.complete(dispatch.item)
        val after = checkNotNull(store.lockForUpdate(tenant, doc, version)).state
        return !InFlight.isInFlight(after)
    }

    private fun accepted(
        dispatch: Dispatch,
        now: java.time.Instant,
    ) {
        val decision =
            VersionTransitions.decide(
                listOf(VersionRecord(dispatch.version, LifecycleState.SENT)),
                Event.ApAccepted(dispatch.version),
            )
        check(decision.transitions.isEmpty() && decision.signals.isEmpty()) {
            "AP accepted ${dispatch.docUuid} version ${dispatch.version}: unexpected decision $decision"
        }
        store.recordAccepted(scope.current(), dispatch.docUuid, dispatch.version, now)
    }

    private fun rejected(
        dispatch: Dispatch,
        result: TransportResult.Rejected,
    ) {
        val code = checkNotNull(result.reasons.firstOrNull()?.code) { "rejection of ${dispatch.docUuid} has no reason" }
        val decision =
            VersionTransitions.decide(
                listOf(VersionRecord(dispatch.version, LifecycleState.SENT)),
                Event.Rejected(dispatch.version, RejectionSource.AP, result.reasons),
            )
        check(
            decision.transitions == listOf(Transition(dispatch.version, LifecycleState.SENT, LifecycleState.REJECTED)),
        ) {
            "rejection of ${dispatch.docUuid} version ${dispatch.version}: unexpected decision $decision"
        }
        store.setState(scope.current(), dispatch.docUuid, dispatch.version, LifecycleState.REJECTED)
        failures.attemptFailed(dispatch, code)
        failures.versionFailed(dispatch, LifecycleState.REJECTED, code)
    }

    private fun transientFailure(
        dispatch: Dispatch,
        row: LockedSubmission,
        code: String,
    ) {
        val tenant = scope.current()
        val now = clock.instant()
        val input =
            FailureInput(
                docUuid = dispatch.docUuid,
                version = dispatch.version,
                state = if (row.retryFailures == 0) LifecycleState.QUEUED else LifecycleState.RETRY,
                failures = row.retryFailures,
                firstFailureAt = row.retryFirstFailureAt,
                now = now,
                boundary = boundaries.boundary(tenant, row.entityRef),
                policy = BackoffPolicy.DEFAULT,
                jitter = jitter.draw(),
            )
        when (val planned = RetryPlanner.onTransientFailure(input)) {
            is FailureResult.Retry -> {
                val schedule = RetrySchedule(planned.nextAttemptAt, planned.attempt, row.retryFirstFailureAt ?: now)
                store.scheduleRetry(tenant, dispatch.docUuid, dispatch.version, schedule)
                failures.attemptFailed(dispatch, code)
            }

            is FailureResult.DeadLetter -> {
                deadLettered(dispatch, planned, code)
            }

            is FailureResult.Anomaly -> {
                error("transient failure of ${dispatch.docUuid} version ${dispatch.version}: anomaly ${planned.reason}")
            }
        }
    }

    /**
     * Writes the dead letter: the failure class is the code of the one signal raised with it (INV-REQ-575, ruling of
     * owner 2026-10-14). A dead letter with no signal or several throws before any write, and the transaction rolls
     * back (fail closed).
     */
    internal fun deadLettered(
        dispatch: Dispatch,
        planned: FailureResult.DeadLetter,
        code: String,
    ) {
        val tenant = scope.current()
        val signal =
            checkNotNull(planned.signals.singleOrNull()) {
                "dead letter of ${dispatch.docUuid} version ${dispatch.version} carries ${planned.signals.size} " +
                    "signals, expected one"
            }
        store.setState(tenant, dispatch.docUuid, dispatch.version, LifecycleState.DEAD_LETTERED)
        store.recordFailureClass(tenant, dispatch.docUuid, dispatch.version, dispatch.attemptNumber, signal.code.value)
        failures.attemptFailed(dispatch, code)
        failures.versionFailed(dispatch, LifecycleState.DEAD_LETTERED, null)
    }

    private companion object {
        private val LOG = System.getLogger(AttemptOutcomeApplier::class.java.name)
    }
}
