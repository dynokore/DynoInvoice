package com.dynokore.platform.adapterruntime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

class ResiliencePolicyTest {
    @Test
    fun `TimeoutPolicy rejects a non-positive timeout`() {
        shouldThrow<IllegalArgumentException> { TimeoutPolicy(Duration.ZERO) }
        shouldThrow<IllegalArgumentException> { TimeoutPolicy(Duration.ofSeconds(-1)) }
        TimeoutPolicy(Duration.ofSeconds(1)).timeout shouldBe Duration.ofSeconds(1)
    }

    @Test
    fun `RetryPolicy enforces its invariants`() {
        val ok = RetryPolicy(maxAttempts = 3, initialBackoff = Duration.ofMillis(100), maxBackoff = Duration.ofSeconds(1), jitter = 0.5)
        ok.maxAttempts shouldBe 3

        shouldThrow<IllegalArgumentException> {
            RetryPolicy(maxAttempts = 0, initialBackoff = Duration.ofMillis(100), maxBackoff = Duration.ofSeconds(1), jitter = 0.5)
        }
        shouldThrow<IllegalArgumentException> {
            // maxBackoff below initialBackoff
            RetryPolicy(maxAttempts = 3, initialBackoff = Duration.ofSeconds(2), maxBackoff = Duration.ofSeconds(1), jitter = 0.5)
        }
        shouldThrow<IllegalArgumentException> {
            RetryPolicy(maxAttempts = 3, initialBackoff = Duration.ofMillis(100), maxBackoff = Duration.ofSeconds(1), jitter = 1.5)
        }
    }

    @Test
    fun `CircuitBreakerPolicy enforces its invariants`() {
        shouldThrow<IllegalArgumentException> {
            CircuitBreakerPolicy(
                failureRateThreshold = 0F,
                slidingWindowSize = 10,
                waitDurationInOpenState = Duration.ofSeconds(30),
                permittedCallsInHalfOpenState = 2,
            )
        }
        shouldThrow<IllegalArgumentException> {
            CircuitBreakerPolicy(
                failureRateThreshold = 50F,
                slidingWindowSize = 0,
                waitDurationInOpenState = Duration.ofSeconds(30),
                permittedCallsInHalfOpenState = 2,
            )
        }
    }

    @Test
    fun `BulkheadPolicy enforces its invariants`() {
        shouldThrow<IllegalArgumentException> { BulkheadPolicy(maxConcurrentCalls = 0, maxWaitDuration = Duration.ZERO) }
        shouldThrow<IllegalArgumentException> { BulkheadPolicy(maxConcurrentCalls = 5, maxWaitDuration = Duration.ofMillis(-1)) }
        BulkheadPolicy(maxConcurrentCalls = 5, maxWaitDuration = Duration.ZERO).maxConcurrentCalls shouldBe 5
    }
}
