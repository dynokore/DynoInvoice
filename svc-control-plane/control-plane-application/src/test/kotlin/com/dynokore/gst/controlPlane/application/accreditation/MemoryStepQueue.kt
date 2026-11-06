package com.dynokore.gst.controlPlane.application.accreditation

import com.dynokore.gst.controlPlane.application.pack.RequestContext
import java.time.Instant

/** An in-memory `accreditation_step_pending`, with the same claim, bookkeeping and exhaustion rules as the table. */
class MemoryStepQueue(
    private val policy: StepRetryPolicy,
) : AccreditationStepQueue {
    data class Row(
        val step: PendingStep,
        val status: String = "PENDING",
        val attempts: Int = 0,
        val nextAttemptAt: Instant,
        val lastError: String? = null,
    )

    val rows = LinkedHashMap<ReleaseTarget, Row>()
    var claimed = emptySet<ReleaseTarget>()

    fun insert(step: PendingStep) {
        rows.putIfAbsent(step.target, Row(step, nextAttemptAt = step.createdAt.plus(policy.firstDelay)))
    }

    override fun due(
        now: Instant,
        limit: Int,
    ) = rows.values
        .filter { it.status == "PENDING" && it.nextAttemptAt <= now }
        .sortedBy { it.step.createdAt }
        .take(limit)
        .map { it.step.target }

    override fun claim(
        target: ReleaseTarget,
        now: Instant,
    ): PendingStep? =
        rows[target]?.takeIf { it.status == "PENDING" && it.nextAttemptAt <= now && target !in claimed }?.step

    override fun delete(target: ReleaseTarget) {
        rows.remove(target)
    }

    override fun recordFailure(
        target: ReleaseTarget,
        error: String,
        now: Instant,
    ): Boolean {
        val row = rows[target]?.takeIf { it.status == "PENDING" } ?: return false
        val attempts = row.attempts + 1
        val exhausted = attempts >= policy.maxAttempts
        rows[target] =
            row.copy(
                status = if (exhausted) "EXHAUSTED" else "PENDING",
                attempts = attempts,
                nextAttemptAt = now.plus(policy.backoff(attempts)),
                lastError = error,
            )
        return exhausted
    }

    companion object {
        fun step(
            target: ReleaseTarget,
            flag: Boolean,
            at: Instant,
        ) = PendingStep(target, flag, RequestContext("corr", "cause"), at)
    }
}
