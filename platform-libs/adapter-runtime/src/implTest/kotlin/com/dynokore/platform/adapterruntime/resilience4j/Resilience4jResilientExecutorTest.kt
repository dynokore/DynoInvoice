package com.dynokore.platform.adapterruntime.resilience4j

import com.dynokore.platform.adapterruntime.BulkheadPolicy
import com.dynokore.platform.adapterruntime.CircuitBreakerPolicy
import com.dynokore.platform.adapterruntime.ResiliencePolicy
import com.dynokore.platform.adapterruntime.RetryPolicy
import com.dynokore.platform.adapterruntime.TimeoutPolicy
import com.dynokore.platform.adapterruntime.deadletter.InMemoryDeadLetterSink
import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class Resilience4jResilientExecutorTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-27T00:00:00Z"), ZoneOffset.UTC)

    private fun policy(
        maxAttempts: Int = 3,
        timeout: Duration = Duration.ofSeconds(5),
        slidingWindowSize: Int = 2,
        maxConcurrentCalls: Int = 4,
        maxWaitDuration: Duration = Duration.ofMillis(200),
    ) = ResiliencePolicy(
        timeout = TimeoutPolicy(timeout),
        retry = RetryPolicy(maxAttempts, Duration.ofMillis(5), Duration.ofMillis(20), jitter = 0.0),
        circuitBreaker = CircuitBreakerPolicy(50F, slidingWindowSize, Duration.ofSeconds(10), 1),
        bulkhead = BulkheadPolicy(maxConcurrentCalls, maxWaitDuration),
    )

    @Test
    fun `a call that succeeds first time is returned as-is, with nothing dead-lettered`() {
        val deadLetters = InMemoryDeadLetterSink()
        val executor = Resilience4jResilientExecutor(policy(), "edge-a", deadLetters, clock)

        executor.execute { "ok" } shouldBe "ok"

        deadLetters.entries shouldBe emptyList()
    }

    @Test
    fun `a call that fails and then succeeds is retried, not dead-lettered`() {
        val deadLetters = InMemoryDeadLetterSink()
        // slidingWindowSize is raised well past maxAttempts: retry wraps the circuit
        // breaker (each retry attempt is itself one recorded call, matching
        // Resilience4j's own documented Retry+CircuitBreaker composition), so the
        // breaker's *own* minimumNumberOfCalls must not be reachable from a single
        // execute() call's retries — otherwise this test would be exercising the
        // breaker tripping mid-retry, not the retry-then-succeed behavior it names.
        val executor =
            Resilience4jResilientExecutor(policy(maxAttempts = 3, slidingWindowSize = 10), "edge-b", deadLetters, clock)
        var calls = 0

        val result =
            executor.execute {
                calls += 1
                if (calls < 3) error("transient") else "ok"
            }

        result shouldBe "ok"
        calls shouldBe 3
        deadLetters.entries shouldBe emptyList()
    }

    @Test
    fun `bounded retry ending in dead-letter (INV-REQ-1353) - every attempt fails, the last failure is rethrown`() {
        val deadLetters = InMemoryDeadLetterSink()
        // See the sibling retry test above for why slidingWindowSize must clear
        // maxAttempts here too: otherwise the breaker opens on this call's own second
        // attempt and the third attempt is refused by CallNotPermittedException
        // instead of retried, so the "every attempt fails" case never runs to its
        // bound and the wrong exception type is what actually gets rethrown.
        val executor =
            Resilience4jResilientExecutor(policy(maxAttempts = 3, slidingWindowSize = 10), "edge-c", deadLetters, clock)
        var calls = 0

        shouldThrow<IllegalStateException> {
            executor.execute {
                calls += 1
                error("always fails")
            }
        }

        calls shouldBe 3
        deadLetters.entries.size shouldBe 1
        deadLetters.entries.single().attempts shouldBe 3
        deadLetters.entries.single().failureType shouldContain "IllegalStateException"
    }

    @Test
    fun `a call slower than the timeout is dead-lettered as a timeout, not run to completion`() {
        val deadLetters = InMemoryDeadLetterSink()
        val executor =
            Resilience4jResilientExecutor(
                policy(maxAttempts = 1, timeout = Duration.ofMillis(50)),
                "edge-d",
                deadLetters,
                clock,
            )

        shouldThrow<TimeoutException> {
            executor.execute {
                Thread.sleep(2_000)
                "too slow"
            }
        }

        deadLetters.entries.single().failureType shouldContain "TimeoutException"
    }

    @Test
    fun `an open circuit breaker fails fast, without invoking the block or spending the retry budget`() {
        val deadLetters = InMemoryDeadLetterSink()
        val executor =
            Resilience4jResilientExecutor(policy(maxAttempts = 1, slidingWindowSize = 2), "edge-e", deadLetters, clock)
        val invocations = AtomicInteger(0)
        val failingBlock: () -> String = {
            invocations.incrementAndGet()
            error("boom")
        }

        repeat(2) {
            shouldThrow<IllegalStateException> { executor.execute(failingBlock) }
        } // fills the 2-call sliding window, all failing
        invocations.get() shouldBe 2

        shouldThrow<CallNotPermittedException> { executor.execute(failingBlock) }
        invocations.get() shouldBe 2 // the breaker refused the call before the block ever ran

        // ignoreExceptions(CallNotPermittedException): no retry spent on an open breaker
        deadLetters.entries.last().attempts shouldBe 1
        deadLetters.entries.last().failureType shouldContain "CallNotPermittedException"
    }

    @Test
    fun `a circuit breaker that opens mid-retry ends one call's own bounded retry early (INV-REQ-1353)`() {
        val deadLetters = InMemoryDeadLetterSink()
        // The composed scenario the two retry-behavior tests above deliberately avoid:
        // here slidingWindowSize (2) is smaller than maxAttempts (5) on purpose, so the
        // breaker's own window IS exhausted by this one call's retries and trips before
        // the retry budget does.
        val executor =
            Resilience4jResilientExecutor(policy(maxAttempts = 5, slidingWindowSize = 2), "edge-g", deadLetters, clock)
        var calls = 0

        shouldThrow<CallNotPermittedException> {
            executor.execute {
                calls += 1
                error("always fails")
            }
        }

        // the block ran for attempts 1 and 2 only; both failures filled the 2-call window and tripped the breaker
        calls shouldBe 2
        deadLetters.entries.single().attempts shouldBe 3 // 2 real attempts, then the 3rd was the breaker's own refusal
        deadLetters.entries.single().failureType shouldContain "CallNotPermittedException"
    }

    @Test
    fun `a bulkhead at capacity refuses a concurrent call instead of queuing it indefinitely`() {
        val deadLetters = InMemoryDeadLetterSink()
        val executor =
            Resilience4jResilientExecutor(
                policy(maxAttempts = 1, maxConcurrentCalls = 1, maxWaitDuration = Duration.ZERO),
                "edge-f",
                deadLetters,
                clock,
            )
        val holdingCallStarted = CountDownLatch(1)
        val releaseHoldingCall = CountDownLatch(1)
        val runner = Executors.newSingleThreadExecutor()

        try {
            val holding =
                runner.submit(
                    Callable {
                        executor.execute {
                            holdingCallStarted.countDown()
                            releaseHoldingCall.await(5, TimeUnit.SECONDS)
                            "done"
                        }
                    },
                )
            holdingCallStarted.await(5, TimeUnit.SECONDS)

            shouldThrow<BulkheadFullException> { executor.execute { "should never run" } }

            releaseHoldingCall.countDown()
            holding.get(5, TimeUnit.SECONDS) shouldBe "done"
        } finally {
            runner.shutdown()
        }

        deadLetters.entries.single().failureType shouldContain "BulkheadFullException"
    }
}
