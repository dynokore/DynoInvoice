package com.dynokore.platform.adapterruntime.resilience4j

import com.dynokore.platform.adapterruntime.BulkheadPolicy
import com.dynokore.platform.adapterruntime.CircuitBreakerPolicy
import com.dynokore.platform.adapterruntime.DeadLetterEntry
import com.dynokore.platform.adapterruntime.DeadLetterSink
import com.dynokore.platform.adapterruntime.ResiliencePolicy
import com.dynokore.platform.adapterruntime.ResilientExecutor
import com.dynokore.platform.adapterruntime.RetryPolicy
import com.dynokore.platform.adapterruntime.TimeoutPolicy
import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import io.github.resilience4j.timelimiter.TimeLimiter
import io.github.resilience4j.timelimiter.TimeLimiterConfig
import java.time.Clock
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The one shipped [ResilientExecutor]: STK-24's Resilience4j core, composed directly
 * (adapter-runtime uses no other L1 module, Keel INV-ADR-2774, so it cannot reuse
 * `service-runtime`'s internal-edge `ResilienceRegistry`; this is the equivalent
 * primitive for an *external* edge).
 *
 * Each [execute] call runs, from the inside out: the block, under a per-call
 * [TimeoutPolicy.timeout] on a fresh virtual thread (never a fixed thread pool,
 * CS-32); through [BulkheadPolicy]'s semaphore bulkhead, bounding concurrent calls on
 * this edge; through [CircuitBreakerPolicy]'s breaker, recording the outcome; the
 * whole chain retried up to [RetryPolicy.maxAttempts] times with jittered exponential
 * backoff — except when the breaker is open, which fails the attempt immediately
 * rather than spending a retry waiting on it. Once retry is exhausted, one
 * [DeadLetterEntry] goes to [deadLetterSink] and the last failure is rethrown
 * (INV-REQ-1353).
 *
 * [clock] is supplied by the caller (CS-10); this class never reads the system clock.
 * [close] shuts down the executor this instance owns; call it once, when the owning
 * adapter version itself stops.
 */
class Resilience4jResilientExecutor(
    override val policy: ResiliencePolicy,
    private val edge: String,
    private val deadLetterSink: DeadLetterSink,
    private val clock: Clock,
    private val executorService: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : ResilientExecutor,
    AutoCloseable {
    private val timeLimiter = TimeLimiter.of(edge, policy.timeout.toResilience4j())
    private val bulkhead = Bulkhead.of(edge, policy.bulkhead.toResilience4j())
    private val circuitBreaker = CircuitBreaker.of(edge, policy.circuitBreaker.toResilience4j())
    private val retry = Retry.of(edge, policy.retry.toResilience4j())

    override fun <T> execute(block: () -> T): T {
        val timeLimited: Callable<T> =
            Callable { timeLimiter.executeFutureSupplier { executorService.submit(Callable(block)) } }
        val bulkheaded: Callable<T> = Bulkhead.decorateCallable(bulkhead, timeLimited)
        val guarded: Callable<T> = CircuitBreaker.decorateCallable(circuitBreaker, bulkheaded)
        var attempts = 0
        val retried: Callable<T> =
            Retry.decorateCallable(retry) {
                attempts += 1
                guarded.call()
            }
        // CS-14: caught here because this executor *is* the adapter boundary for this
        // edge — never a broader catch further out. Error (OOM, StackOverflow, ...) is
        // deliberately not caught.
        return try {
            retried.call()
        } catch (e: Exception) {
            deadLetterSink.send(
                DeadLetterEntry(
                    edge = edge,
                    attempts = attempts,
                    failedAt = clock.instant(),
                    failureType = e::class.qualifiedName ?: e.javaClass.name,
                ),
            )
            throw e
        }
    }

    override fun close() {
        executorService.shutdown()
    }
}

// Public, not private: the mapping from this module's framework-free policy types to
// Resilience4j's own config types is exactly what a property test (CS-38) checks
// against arbitrary policies, deterministically and without depending on wall-clock
// timing (src/implTest's ResiliencePolicyMappingPropertiesTest).

fun TimeoutPolicy.toResilience4j(): TimeLimiterConfig = TimeLimiterConfig.custom().timeoutDuration(timeout).build()

fun BulkheadPolicy.toResilience4j(): BulkheadConfig =
    BulkheadConfig
        .custom()
        .maxConcurrentCalls(maxConcurrentCalls)
        .maxWaitDuration(maxWaitDuration)
        .build()

fun CircuitBreakerPolicy.toResilience4j(): CircuitBreakerConfig =
    CircuitBreakerConfig
        .custom()
        .failureRateThreshold(failureRateThreshold)
        .slidingWindowSize(slidingWindowSize)
        // Resilience4j's own default (100) would otherwise apply regardless of how
        // small a window this policy declares, so the breaker would never evaluate
        // it: evaluate as soon as the window itself is full.
        .minimumNumberOfCalls(slidingWindowSize)
        .waitDurationInOpenState(waitDurationInOpenState)
        .permittedNumberOfCallsInHalfOpenState(permittedCallsInHalfOpenState)
        .build()

// The breaker itself already refuses a call fast when open (CallNotPermittedException);
// retrying that outcome would only spend the bounded attempt budget waiting on a
// breaker that will not close sooner for it, so it is the one exception retry ignores.
fun RetryPolicy.toResilience4j(): RetryConfig =
    RetryConfig
        .custom<Any>()
        .maxAttempts(maxAttempts)
        .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(initialBackoff, 2.0, jitter, maxBackoff))
        .ignoreExceptions(CallNotPermittedException::class.java)
        .build()
