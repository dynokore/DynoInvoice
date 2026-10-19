package com.dynokore.platform.outboxandqueue

import java.time.Clock
import java.time.Instant

/**
 * The retry rule of [JdbcInboxConsumer] (INV-Q-3108): after a handler failure the record is rescheduled on the offset
 * row after a bounded, jittered backoff, or dead-lettered when its attempts or its infrastructure patience are spent.
 */
internal class InboxRetry(
    private val subscription: ConsumerSubscription,
    private val clock: Clock,
    private val randomUnit: () -> Double,
    private val offsets: InboxOffsets,
) {
    /** Records the [failure] in the offset row; throws it again when the record was scheduled, not dead-lettered. */
    fun settle(
        record: LogRecord,
        state: AttemptState?,
        failure: Throwable,
    ) {
        if (state == null || !failed(record, state, failure)) throw failure
    }

    /** True when the record was dead-lettered instead of scheduled. */
    private fun failed(
        record: LogRecord,
        state: AttemptState,
        failure: Throwable,
    ): Boolean =
        if (subscription.classifier.classify(failure) == FailureKind.RECORD) {
            recordFailed(record, state, failure)
        } else {
            infrastructureFailed(record, state, failure)
        }

    private fun recordFailed(
        record: LogRecord,
        state: AttemptState,
        failure: Throwable,
    ): Boolean {
        val retry = subscription.retry
        val exhausted = state.attempts >= retry.maxAttempts
        if (exhausted) {
            offsets.deadLetter(record, "$EXHAUSTED after ${state.attempts} attempts: ${failure.javaClass.name}")
        } else {
            offsets.schedule(
                record.offset,
                0,
                state.infraFailures,
                state.firstInfra,
                clock.instant().plus(retry.delayAfter(state.attempts, randomUnit())),
            )
        }
        return exhausted
    }

    private fun infrastructureFailed(
        record: LogRecord,
        state: AttemptState,
        failure: Throwable,
    ): Boolean {
        val retry = subscription.retry
        val now = clock.instant()
        val first: Instant = state.firstInfra ?: now
        val exhausted = !now.isBefore(first.plus(retry.infrastructureGiveUp))
        if (exhausted) {
            offsets.deadLetter(record, "$INFRA_EXHAUSTED ${retry.infrastructureGiveUp}: ${failure.javaClass.name}")
        } else {
            val infra = state.infraFailures + 1
            offsets.schedule(record.offset, 1, infra, first, now.plus(retry.delayAfter(infra, randomUnit())))
        }
        return exhausted
    }

    companion object {
        const val EXHAUSTED = "handler failed, retries exhausted"
        const val INFRA_EXHAUSTED = "handler failed on infrastructure failures for"
    }
}
