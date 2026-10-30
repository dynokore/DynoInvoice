package com.dynokore.gst.submission.domain.retry

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import java.time.Duration
import java.time.Instant

/** The signal code a transient failure raises (error-codes.yaml SUB-001, WARN, AUTO_RETRY). */
enum class RetryCode(
    val value: String,
) {
    SUB_001("SUB-001"),
}

/** A signal to raise with the result. */
data class Raise(
    val code: RetryCode,
)

enum class DeadLetterReason { RETRY_WINDOW_EXHAUSTED, BOUNDARY_MISSING }

enum class AnomalyReason { FAILURE_IN_STATE, REPLAY_IN_STATE }

/**
 * Exponential backoff with equal jitter (INV-Q-6435 A): [base] times [factor] to the power n minus 1, capped at [cap].
 */
data class BackoffPolicy(
    val base: Duration,
    val factor: Int,
    val cap: Duration,
) {
    init {
        require(!base.isZero && !base.isNegative) { "base must be positive" }
        require(factor >= 2) { "factor must be at least 2" }
        require(cap >= base) { "cap must not be below base" }
    }

    companion object {
        val DEFAULT = BackoffPolicy(Duration.ofSeconds(30), 2, Duration.ofMinutes(15))
    }
}

/**
 * @property failures the failures already spent in the series, 0 for the first failure or the first after a replay.
 * @property firstFailureAt the first failure of the series, present exactly when [failures] is above 0.
 * @property boundary the retry_boundary of the AP connection (VP-48), absent when it is not resolved.
 * @property jitter a draw in [0, 1) made by the caller.
 */
data class FailureInput(
    val docUuid: String,
    val version: Int,
    val state: LifecycleState,
    val failures: Int,
    val firstFailureAt: Instant?,
    val now: Instant,
    val boundary: Duration?,
    val policy: BackoffPolicy,
    val jitter: Double,
) {
    init {
        require(docUuid.isNotBlank()) { "docUuid must not be blank" }
        require(version >= 1) { "version must be at least 1" }
        require(failures >= 0) { "failures must not be negative" }
        require(failures == 0 || firstFailureAt != null) { "firstFailureAt is required when failures is above 0" }
        require(failures > 0 || firstFailureAt == null) { "firstFailureAt must be null when failures is 0" }
        require(firstFailureAt == null || !firstFailureAt.isAfter(now)) { "firstFailureAt must not be after now" }
        require(boundary == null || (!boundary.isZero && !boundary.isNegative)) { "boundary must be positive" }
        require(jitter >= 0.0 && jitter < 1.0) { "jitter must be in [0, 1)" }
    }
}

sealed interface FailureResult {
    data class Retry(
        val docUuid: String,
        val version: Int,
        val from: LifecycleState,
        val attempt: Int,
        val nextAttemptAt: Instant,
        val signals: List<Raise>,
    ) : FailureResult

    data class DeadLetter(
        val docUuid: String,
        val version: Int,
        val from: LifecycleState,
        val reason: DeadLetterReason,
        val signals: List<Raise>,
    ) : FailureResult

    data class Anomaly(
        val reason: AnomalyReason,
    ) : FailureResult
}

sealed interface ReplayResult {
    data class Replayed(
        val docUuid: String,
        val version: Int,
        val from: LifecycleState,
        val to: LifecycleState,
    ) : ReplayResult

    data object Unchanged : ReplayResult

    data class Anomaly(
        val reason: AnomalyReason,
    ) : ReplayResult
}

/**
 * Decides the retry, dead letter and replay of a transient submission failure (INV-REQ-562, INV-REQ-541, INV-REQ-575).
 * Pure: no state, no clock, no random draw. The doc UUID and version of a result are the input's, never new ones.
 */
object RetryPlanner {
    private val ACCEPTING = setOf(LifecycleState.QUEUED, LifecycleState.RETRY)
    private val RAISED = listOf(Raise(RetryCode.SUB_001))

    fun onTransientFailure(input: FailureInput): FailureResult {
        if (input.state !in ACCEPTING) return FailureResult.Anomaly(AnomalyReason.FAILURE_IN_STATE)
        val boundary =
            input.boundary ?: return deadLetter(input, DeadLetterReason.BOUNDARY_MISSING)
        val attempt = input.failures + 1
        val half = ceilingMillis(input.policy, attempt) / 2
        val delay = half + (input.jitter * half).toLong()
        val nextAttemptAt = input.now.plusMillis(delay)
        val windowEnd = (input.firstFailureAt ?: input.now).plus(boundary)
        if (nextAttemptAt.isAfter(windowEnd)) return deadLetter(input, DeadLetterReason.RETRY_WINDOW_EXHAUSTED)
        return FailureResult.Retry(input.docUuid, input.version, input.state, attempt, nextAttemptAt, RAISED)
    }

    fun onReplay(
        docUuid: String,
        version: Int,
        state: LifecycleState,
    ): ReplayResult {
        require(docUuid.isNotBlank()) { "docUuid must not be blank" }
        require(version >= 1) { "version must be at least 1" }
        return when (state) {
            LifecycleState.DEAD_LETTERED ->
                ReplayResult.Replayed(docUuid, version, LifecycleState.DEAD_LETTERED, LifecycleState.QUEUED)
            LifecycleState.QUEUED -> ReplayResult.Unchanged
            else -> ReplayResult.Anomaly(AnomalyReason.REPLAY_IN_STATE)
        }
    }

    private fun deadLetter(
        input: FailureInput,
        reason: DeadLetterReason,
    ) = FailureResult.DeadLetter(input.docUuid, input.version, input.state, reason, RAISED)

    /** The smaller of cap and base times factor^(n-1) in milliseconds, saturating so that it never overflows. */
    private fun ceilingMillis(
        policy: BackoffPolicy,
        attempt: Int,
    ): Long {
        val cap = policy.cap.toMillis()
        var value = policy.base.toMillis()
        var step = 1
        while (step < attempt && value < cap) {
            value = if (value > cap / policy.factor) cap else value * policy.factor
            step++
        }
        return minOf(value, cap)
    }
}
