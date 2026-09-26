package com.dynokore.platform.adapterruntime.resilience4j

import com.dynokore.platform.adapterruntime.RetryPolicy
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Property test (STK-38, CS-38): for every valid [RetryPolicy] — not just a handful
 * of examples — the exponential-backoff-with-jitter interval this module hands
 * Resilience4j (INV-REQ-1353) never exceeds [RetryPolicy.maxBackoff] and is never
 * negative, for every attempt number within [RetryPolicy.maxAttempts].
 */
class ResiliencePolicyMappingPropertiesTest {
    private val retryPolicyArb: Arb<RetryPolicy> =
        Arb.bind(
            Arb.long(1L..500L),
            Arb.long(0L..5_000L),
            Arb.int(1..10),
            Arb.double(0.0..1.0),
        ) { initialMs, extraMs, maxAttempts, jitter ->
            RetryPolicy(
                maxAttempts = maxAttempts,
                initialBackoff = Duration.ofMillis(initialMs),
                maxBackoff = Duration.ofMillis(initialMs + extraMs),
                jitter = jitter,
            )
        }

    @Suppress("DEPRECATION") // RetryConfig.getIntervalFunction() is Resilience4j's only accessor for this; no replacement exists in 2.4.0
    @Test
    fun `every attempt's backoff interval is within 0 and maxBackoff`() =
        runTest {
            checkAll(retryPolicyArb) { retryPolicy ->
                val intervalFunction = retryPolicy.toResilience4j().intervalFunction!!
                val maxBackoffMillis = retryPolicy.maxBackoff.toMillis()

                (1..retryPolicy.maxAttempts).forEach { attempt ->
                    val intervalMillis = intervalFunction.apply(attempt)
                    intervalMillis shouldBeGreaterThanOrEqualTo 0L
                    intervalMillis shouldBeLessThanOrEqualTo maxBackoffMillis
                }
            }
        }
}
