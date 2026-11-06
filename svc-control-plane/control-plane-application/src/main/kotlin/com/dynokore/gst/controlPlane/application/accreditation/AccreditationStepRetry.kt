package com.dynokore.gst.controlPlane.application.accreditation

import com.dynokore.gst.controlPlane.application.pack.RequestContext
import java.time.Duration
import java.time.Instant

/**
 * The durable retry of the accreditation step (INV-Q-17708 A, the transactional outbox pattern with at-least-once
 * delivery and an idempotent handler). Every value is configuration (the properties `gst.accreditation-step.*`); no
 * class holds a delay or a limit as a literal.
 */
data class StepRetryPolicy(
    val firstDelay: Duration,
    val firstBackoff: Duration,
    val backoffCap: Duration,
    val maxAttempts: Int,
    val batchSize: Int,
) {
    init {
        require(!firstDelay.isNegative) { "the first delay is negative" }
        require(!firstBackoff.isNegative && !firstBackoff.isZero) { "the first backoff is not positive" }
        require(backoffCap >= firstBackoff) { "the backoff cap is below the first backoff" }
        require(maxAttempts in 1..Int.MAX_VALUE) { "the maximum attempts is below 1" }
        require(batchSize in 1..Int.MAX_VALUE) { "the batch size is below 1" }
    }

    /** The wait after the [attempts]-th failed attempt: the first backoff doubled per further failure, capped. */
    fun backoff(attempts: Int): Duration {
        var wait = firstBackoff
        repeat(attempts - 1) { if (wait < backoffCap) wait = wait.multipliedBy(2) }
        return if (wait > backoffCap) backoffCap else wait
    }
}

/** The pending step stored with the release record, in the release transaction (outbox dual write). */
data class PendingStep(
    val target: ReleaseTarget,
    val assessmentRequested: Boolean,
    val request: RequestContext,
    val createdAt: Instant,
)

/** The table `accreditation_step_pending`: one row per release whose step has not yet ended its work. */
interface AccreditationStepQueue {
    /** The targets of the rows PENDING and due at [now], oldest first, at most [limit]. */
    fun due(
        now: Instant,
        limit: Int,
    ): List<ReleaseTarget>

    /** Locks the row of [target] when it is PENDING, due at [now] and not locked by another run; else null. */
    fun claim(
        target: ReleaseTarget,
        now: Instant,
    ): PendingStep?

    /** Deletes the row of [target] (the work ended: a task opened, a task linked, or no trigger). */
    fun delete(target: ReleaseTarget)

    /**
     * Adds 1 to the attempts of [target], sets the next attempt by the backoff and the last error; true when the row
     * became EXHAUSTED because the attempts reached the maximum.
     */
    fun recordFailure(
        target: ReleaseTarget,
        error: String,
        now: Instant,
    ): Boolean
}

/** What the step reports; the bootstrap binds it to the log. */
interface StepReporting {
    fun failed(
        target: ReleaseTarget,
        failure: Exception,
    )

    fun bookkeepingFailed(
        target: ReleaseTarget,
        failure: Exception,
    )

    fun exhausted(
        target: ReleaseTarget,
        error: String,
    )
}
