package com.dynokore.platform.outboxandqueue

import java.time.Duration

/**
 * How a consumer redelivers a record whose handler failed (ARC 4.2 step 5, Keel INV-Q-2966): the failure rolls the
 * record's transaction back, the record is tried again after a backoff that grows by [multiplier] from
 * [initialDelay] up to [maxDelay], each delay reduced by a random share of at most [jitter] so that replicas do
 * not retry in step, and after [maxAttempts] failed attempts the record goes to `event_dead_letter` and the
 * consumer moves on. Nothing is retried for ever. A [ContractViolation] is never retried.
 *
 * A failure the [FailureClassifier] calls infrastructure (INV-Q-3110) does not use the bound: it waits the same
 * growing delays up to [maxDelay], and the record is dead-lettered only when infrastructure failures have lasted
 * [infrastructureGiveUp] since the first one.
 *
 * The values are library settings, set per consumer through [ConsumerSubscription.retry]; they are not variation
 * points (ARC-12). The attempt count lives on the consumer's offset row (INV-Q-3108, `OutboxSchema.upgradeTo140`),
 * so it survives a restart, a kill during the handler and is shared by replicas.
 */
data class ConsumerRetryPolicy(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val initialDelay: Duration = Duration.ofSeconds(1),
    val multiplier: Double = 2.0,
    val maxDelay: Duration = DEFAULT_MAX_DELAY,
    val jitter: Double = DEFAULT_JITTER,
    val infrastructureGiveUp: Duration = Duration.ofHours(DEFAULT_GIVE_UP_HOURS),
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(!initialDelay.isNegative && !initialDelay.isZero) { "initialDelay must be positive" }
        require(multiplier >= 1.0) { "multiplier must be at least 1" }
        require(maxDelay >= initialDelay) { "maxDelay must not be below initialDelay" }
        require(infrastructureGiveUp > Duration.ZERO) { "infrastructureGiveUp must be > 0" }
        require(jitter >= 0.0 && jitter <= 1.0) { "jitter must be within 0..1" }
    }

    /**
     * The wait before the attempt that follows failed attempt number [failedAttempt] (1-based): the capped
     * exponential delay, less [randomUnit] (a value in 0..1) times [jitter] of itself.
     */
    fun delayAfter(
        failedAttempt: Int,
        randomUnit: Double,
    ): Duration {
        require(failedAttempt >= 1) { "failedAttempt is 1-based" }
        val grown = initialDelay.toMillis() * Math.pow(multiplier, (failedAttempt - 1).toDouble())
        val capped = minOf(grown, maxDelay.toMillis().toDouble())
        return Duration.ofMillis(Math.round(capped * (1.0 - jitter * randomUnit.coerceIn(0.0, 1.0))))
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 8
        const val DEFAULT_JITTER = 0.5
        private const val DEFAULT_GIVE_UP_HOURS = 24L
        private const val DEFAULT_MAX_DELAY_MINUTES = 5L
        private val DEFAULT_MAX_DELAY: Duration = Duration.ofMinutes(DEFAULT_MAX_DELAY_MINUTES)
        val DEFAULT = ConsumerRetryPolicy()
    }
}
