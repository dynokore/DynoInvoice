package com.dynokore.platform.serviceruntime.resilience.resilience4j

import com.dynokore.platform.serviceruntime.resilience.RetryBudget
import java.time.Clock

/**
 * Counts, per edge, the first attempts and retries started inside a sliding [RetryBudget.window] (INV-T-9099). A retry
 * is granted only while the retries started in the window, this one included, stay at most `maxRetryRatio` times the
 * first attempts started in it. Time comes from [clock] (CS-10).
 */
internal class RetryBudgetTracker(
    private val budget: RetryBudget,
    private val clock: Clock,
) {
    private val firsts = ArrayDeque<Long>()
    private val retries = ArrayDeque<Long>()

    @Synchronized
    fun firstAttemptStarted() {
        val now = clock.millis()
        evict(now)
        firsts.addLast(now)
    }

    /** True, and the retry is counted, when the budget allows one more retry now. */
    @Synchronized
    fun tryAcquireRetry(): Boolean {
        val now = clock.millis()
        evict(now)
        if ((retries.size + 1) > budget.maxRetryRatio * firsts.size) return false
        retries.addLast(now)
        return true
    }

    private fun evict(now: Long) {
        val cutoff = now - budget.window.toMillis()
        while (firsts.isNotEmpty() && firsts.first() <= cutoff) firsts.removeFirst()
        while (retries.isNotEmpty() && retries.first() <= cutoff) retries.removeFirst()
    }
}
