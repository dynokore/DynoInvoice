package com.dynokore.platform.adapterruntime

import java.time.Duration

/** How long a single attempt is given before it is timed out (INV-REQ-1353). */
data class TimeoutPolicy(
    val timeout: Duration,
) {
    init {
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive, was $timeout" }
    }
}

/**
 * Bounded retry with exponential backoff and jitter (INV-REQ-1353). The interval
 * before attempt *n* is drawn from `initialBackoff * 2^(n-1)`, randomised by
 * [jitter] and capped at [maxBackoff]. Only ever configured for idempotent
 * operations (CS-16) — that discipline is the caller's, not this type's.
 */
data class RetryPolicy(
    val maxAttempts: Int,
    val initialBackoff: Duration,
    val maxBackoff: Duration,
    val jitter: Double,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1, was $maxAttempts" }
        require(!initialBackoff.isNegative && !initialBackoff.isZero) {
            "initialBackoff must be positive, was $initialBackoff"
        }
        require(maxBackoff >= initialBackoff) {
            "maxBackoff ($maxBackoff) must be at least initialBackoff ($initialBackoff)"
        }
        require(jitter in 0.0..1.0) { "jitter must be within 0.0..1.0, was $jitter" }
    }
}

/**
 * Trips after a share of recent calls fail, so further calls fail fast instead of
 * piling up against a struggling external system (INV-REQ-1353).
 */
data class CircuitBreakerPolicy(
    val failureRateThreshold: Float,
    val slidingWindowSize: Int,
    val waitDurationInOpenState: Duration,
    val permittedCallsInHalfOpenState: Int,
) {
    init {
        require(failureRateThreshold in FAILURE_RATE_THRESHOLD_MIN..FAILURE_RATE_THRESHOLD_MAX) {
            "failureRateThreshold must be a percentage within 1..100, was $failureRateThreshold"
        }
        require(slidingWindowSize >= 1) { "slidingWindowSize must be at least 1, was $slidingWindowSize" }
        require(!waitDurationInOpenState.isNegative) {
            "waitDurationInOpenState must not be negative, was $waitDurationInOpenState"
        }
        require(permittedCallsInHalfOpenState >= 1) {
            "permittedCallsInHalfOpenState must be at least 1, was $permittedCallsInHalfOpenState"
        }
    }

    companion object {
        private const val FAILURE_RATE_THRESHOLD_MIN = 1.0F
        private const val FAILURE_RATE_THRESHOLD_MAX = 100.0F
    }
}

/** Bounds how many calls on one edge run at once, queuing (briefly) or refusing the rest (INV-REQ-1353). */
data class BulkheadPolicy(
    val maxConcurrentCalls: Int,
    val maxWaitDuration: Duration,
) {
    init {
        require(maxConcurrentCalls >= 1) { "maxConcurrentCalls must be at least 1, was $maxConcurrentCalls" }
        require(!maxWaitDuration.isNegative) { "maxWaitDuration must not be negative, was $maxWaitDuration" }
    }
}

/** The full STK-24 policy for one external edge: timeout, retry, circuit breaker and bulkhead together. */
data class ResiliencePolicy(
    val timeout: TimeoutPolicy,
    val retry: RetryPolicy,
    val circuitBreaker: CircuitBreakerPolicy,
    val bulkhead: BulkheadPolicy,
)
