package com.dynokore.platform.serviceruntime.resilience

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The remaining time budget for a chain of synchronous calls (FR-AH.7,
 * INV-REQ-1203): created once, near the edge of the system — the request that
 * starts the chain, or a job that starts one with no inbound request at all —
 * and threaded explicitly down through every port and adapter on the call path
 * (TPL-07's own `deadline: Deadline` parameter), never read from a global by
 * application code. [ResilientEdge.execute] is the one place this module
 * itself binds it into ambient scope, and only so the generated client's own
 * interceptor can read it (API-29) — a mechanism, not a second way for
 * application code to obtain one.
 *
 * [remaining] is always non-negative: once elapsed it stays at [Duration.ZERO],
 * never negative (ARC-13: time comes from the caller's own [Clock], never a
 * wall-clock read here).
 */
data class Deadline(private val deadlineAt: Instant) {
    /** How much of the budget is left, floored at zero (API-29). */
    fun remaining(clock: Clock): Duration {
        val left = Duration.between(clock.instant(), deadlineAt)
        return if (left.isNegative) Duration.ZERO else left
    }

    /** True once [remaining] has reached zero — the point FR-AH.7 behaviour #3's degradation outcome applies from. */
    fun isExpired(clock: Clock): Boolean = remaining(clock).isZero

    companion object {
        /** API-29: the header a call's remaining budget travels on. */
        const val HEADER_NAME = "X-Deadline-Ms"

        /** A fresh deadline, [budget] from now — typically the ingress edge of a request or job. */
        fun after(
            budget: Duration,
            clock: Clock,
        ): Deadline {
            require(!budget.isNegative) { "budget must not be negative, was $budget" }
            return Deadline(clock.instant().plus(budget))
        }

        /** Reconstructs a deadline from a propagated [HEADER_NAME] value: [remainingMillis] from now. */
        fun ofRemainingMillis(
            remainingMillis: Long,
            clock: Clock,
        ): Deadline = after(Duration.ofMillis(remainingMillis.coerceAtLeast(0)), clock)
    }
}
