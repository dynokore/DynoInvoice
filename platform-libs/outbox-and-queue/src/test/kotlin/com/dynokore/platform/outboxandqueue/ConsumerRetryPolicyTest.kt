package com.dynokore.platform.outboxandqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** The backoff arithmetic of [ConsumerRetryPolicy] (ARC 4.2 step 5, INV-Q-2966): increasing, jittered, bounded. */
class ConsumerRetryPolicyTest {
    private val policy =
        ConsumerRetryPolicy(
            maxAttempts = 6,
            initialDelay = Duration.ofSeconds(1),
            multiplier = 2.0,
            maxDelay = Duration.ofSeconds(10),
            jitter = 0.5,
        )

    @Test
    fun `without jitter the delay doubles and stops at the cap`() {
        val delays = (1..6).map { policy.delayAfter(it, randomUnit = 0.0).toMillis() }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 10000L, 10000L), delays)
    }

    @Test
    fun `jitter only shortens a delay, by at most its share, and stays increasing until the cap`() {
        for (attempt in 1..6) {
            val full = policy.delayAfter(attempt, 0.0).toMillis()
            val shortest = policy.delayAfter(attempt, 1.0).toMillis()
            assertEquals(full / 2, shortest)
            assertTrue(policy.delayAfter(attempt, 0.37).toMillis() in shortest..full)
        }
        // With the default jitter share a later step's shortest wait is not below the earlier step's longest.
        assertTrue(policy.delayAfter(2, 1.0) >= policy.delayAfter(1, 0.0))
    }

    @Test
    fun `settings that would retry for ever or never wait are refused`() {
        assertThrows(IllegalArgumentException::class.java) { ConsumerRetryPolicy(maxAttempts = 0) }
        assertThrows(IllegalArgumentException::class.java) { ConsumerRetryPolicy(initialDelay = Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { ConsumerRetryPolicy(multiplier = 0.5) }
        assertThrows(IllegalArgumentException::class.java) { ConsumerRetryPolicy(jitter = 1.5) }
        assertThrows(IllegalArgumentException::class.java) {
            ConsumerRetryPolicy(initialDelay = Duration.ofSeconds(2), maxDelay = Duration.ofSeconds(1))
        }
    }
}
