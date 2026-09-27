package com.dynokore.platform.serviceruntime.resilience.resilience4j

import com.dynokore.platform.serviceruntime.resilience.BulkheadPolicy
import com.dynokore.platform.serviceruntime.resilience.CircuitBreakerPolicy
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.EdgePolicy
import com.dynokore.platform.serviceruntime.resilience.RetryPolicy
import com.dynokore.platform.serviceruntime.resilience.TimeoutPolicy
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentEndUserToken
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.web.client.ResourceAccessException
import java.net.ConnectException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * INV-REQ-1203, INV-REQ-1204: the internal-edge counterpart of
 * `adapter-runtime`'s own `Resilience4jResilientExecutorTest` (INV-T-2862 / T18),
 * adapted for this type's own two differences — a [Deadline] bound around every
 * call, and three typed fallbacks instead of a dead-lettered rethrow.
 */
class Resilience4jResilientEdgeTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-27T00:00:00Z"), ZoneOffset.UTC)
    private val generousDeadline = Deadline.after(Duration.ofSeconds(30), clock)

    private fun edge(
        retryMaxAttempts: Int? = null,
        timeout: Duration = Duration.ofSeconds(5),
        slidingWindowSize: Int = 2,
        maxConcurrentCalls: Int = 4,
        maxWaitDuration: Duration = Duration.ofMillis(200),
        name: String = "edge-${System.nanoTime()}",
    ) = Resilience4jResilientEdge(
        name,
        EdgePolicy(
            timeout = TimeoutPolicy(timeout),
            retry = retryMaxAttempts?.let { RetryPolicy(it, Duration.ofMillis(5), Duration.ofMillis(20), jitter = 0.0) },
            circuitBreaker = CircuitBreakerPolicy(50F, slidingWindowSize, Duration.ofSeconds(10), 1),
            bulkhead = BulkheadPolicy(maxConcurrentCalls, maxWaitDuration),
        ),
        clock,
    )

    private fun Resilience4jResilientEdge.callWithFallbacks(
        deadline: Deadline = generousDeadline,
        block: () -> String,
    ): String = execute(
        deadline,
        onCallNotPermitted = { "not-permitted" },
        onConnectFailure = { "connect-failure" },
        onTimeout = { "timed-out" },
        block = block,
    )

    @Test
    fun `a call that succeeds first time is returned as-is`() {
        edge().callWithFallbacks { "ok" } shouldBe "ok"
    }

    @Test
    fun `a call that fails and then succeeds is retried on an idempotent edge`() {
        val e = edge(retryMaxAttempts = 3, slidingWindowSize = 10)
        var calls = 0

        e.callWithFallbacks {
            calls += 1
            if (calls < 3) throw IllegalStateException("transient") else "ok"
        } shouldBe "ok"
        calls shouldBe 3
    }

    @Test
    fun `an edge with no retry policy never retries a failure`() {
        val e = edge(retryMaxAttempts = null)
        var calls = 0

        shouldThrow<IllegalStateException> {
            e.callWithFallbacks {
                calls += 1
                throw IllegalStateException("boom")
            }
        }
        calls shouldBe 1
    }

    @Test
    fun `bounded retry exhausted rethrows the last business failure unchanged (CS-14, CS-15)`() {
        val e = edge(retryMaxAttempts = 3, slidingWindowSize = 10)
        var calls = 0

        shouldThrow<IllegalStateException> {
            e.callWithFallbacks {
                calls += 1
                throw IllegalStateException("always fails")
            }
        }
        calls shouldBe 3
    }

    @Test
    fun `a call slower than the edge's own timeout returns the timeout fallback, not the result`() {
        val e = edge(timeout = Duration.ofMillis(50))
        val ranToCompletion = AtomicInteger(0)

        e.callWithFallbacks {
            Thread.sleep(2_000)
            ranToCompletion.incrementAndGet()
            "too slow"
        } shouldBe "timed-out"

        ranToCompletion.get() shouldBe 0
    }

    @Test
    fun `an already-expired deadline returns the timeout fallback without attempting the call at all`() {
        val e = edge()
        val alreadyExpired = Deadline.after(Duration.ZERO, clock)
        var invoked = false

        e.callWithFallbacks(deadline = alreadyExpired) {
            invoked = true
            "ok"
        } shouldBe "timed-out"

        invoked shouldBe false
    }

    @Test
    fun `an open circuit breaker returns the call-not-permitted fallback without invoking the block`() {
        val e = edge(slidingWindowSize = 2)
        val invocations = AtomicInteger(0)
        val failingBlock: () -> String = {
            invocations.incrementAndGet()
            throw IllegalStateException("boom")
        }

        repeat(2) { shouldThrow<IllegalStateException> { e.callWithFallbacks(block = failingBlock) } } // fills the 2-call sliding window
        invocations.get() shouldBe 2

        e.callWithFallbacks(block = failingBlock) shouldBe "not-permitted"
        invocations.get() shouldBe 2 // the breaker refused the call before the block ran again
    }

    @Test
    fun `a bulkhead at capacity returns the call-not-permitted fallback for a concurrent call`() {
        val e = edge(maxConcurrentCalls = 1, maxWaitDuration = Duration.ZERO)
        val holdingCallStarted = CountDownLatch(1)
        val releaseHoldingCall = CountDownLatch(1)
        val runner = Executors.newSingleThreadExecutor()

        try {
            val holding = runner.submit(
                Callable {
                    e.callWithFallbacks {
                        holdingCallStarted.countDown()
                        releaseHoldingCall.await(5, TimeUnit.SECONDS)
                        "done"
                    }
                },
            )
            holdingCallStarted.await(5, TimeUnit.SECONDS)

            e.callWithFallbacks { "should never run" } shouldBe "not-permitted"

            releaseHoldingCall.countDown()
            holding.get(5, TimeUnit.SECONDS) shouldBe "done"
        } finally {
            runner.shutdown()
        }
    }

    @Test
    fun `a refused connection returns the connect-failure fallback`() {
        val e = edge()
        e.callWithFallbacks { throw ConnectException("refused") } shouldBe "connect-failure"
    }

    @Test
    fun `a ResourceAccessException wrapping a refused connection also returns the connect-failure fallback`() {
        val e = edge()
        e.callWithFallbacks { throw ResourceAccessException("I/O error", ConnectException("refused")) } shouldBe "connect-failure"
    }

    @Test
    fun `a ResourceAccessException with an unrelated cause propagates unchanged`() {
        val e = edge()
        shouldThrow<ResourceAccessException> {
            e.callWithFallbacks { throw ResourceAccessException("I/O error", java.io.IOException("disk full")) }
        }
    }

    @Test
    fun `binds the deadline, and the ambient end-user token when one is present, around the block even though it runs on its own thread`() {
        val captured = mutableListOf<Pair<Deadline?, String?>>()

        val result = CurrentEndUserToken.callWith("Bearer end-user-token") {
            edge().callWithFallbacks(deadline = generousDeadline) {
                captured.add(CurrentDeadline.current() to CurrentEndUserToken.current())
                "ok"
            }
        }

        result shouldBe "ok"
        captured shouldBe listOf(generousDeadline to "Bearer end-user-token")
    }

    @Test
    fun `binds only the deadline when no ambient end-user token is present`() {
        val captured = mutableListOf<Pair<Deadline?, String?>>()

        edge().callWithFallbacks(deadline = generousDeadline) {
            captured.add(CurrentDeadline.current() to CurrentEndUserToken.current())
            "ok"
        }

        captured shouldBe listOf(generousDeadline to null)
    }
}
