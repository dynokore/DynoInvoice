package com.dynokore.platform.serviceruntime.resilience.resilience4j

import com.dynokore.platform.serviceruntime.resilience.BulkheadPolicy
import com.dynokore.platform.serviceruntime.resilience.CircuitBreakerPolicy
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.EdgePolicy
import com.dynokore.platform.serviceruntime.resilience.ResilientEdge
import com.dynokore.platform.serviceruntime.resilience.RetryPolicy
import com.dynokore.platform.serviceruntime.resilience.TimeoutPolicy
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentEndUserToken
import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import io.github.resilience4j.timelimiter.TimeLimiter
import io.github.resilience4j.timelimiter.TimeLimiterConfig
import org.springframework.web.client.ResourceAccessException
import java.net.ConnectException
import java.time.Clock
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException

/**
 * The one shipped [ResilientEdge]: STK-24's Resilience4j core, composed exactly
 * the way `adapter-runtime`'s own `Resilience4jResilientExecutor` composes it for
 * an external edge (INV-T-2862 / T18) — service-runtime cannot depend on that
 * module (§18.9, this module is first in `L1_MODULE_ORDER`), so this is the
 * equivalent primitive for an *internal* one, with two differences a §2.5 edge
 * needs and an external one does not: [execute] takes a [Deadline], bound into
 * ambient scope for the call's own duration so the generated client's own
 * interceptor can propagate it (API-29); and it returns one of three typed
 * fallbacks instead of a dead-lettered rethrow — an internal edge's caller is
 * always this platform's own code, which TPL-07 already asks to handle a
 * degraded outcome as a value, not an exception.
 *
 * From the inside out, one [execute] call runs: the block, on a fresh virtual
 * thread ([executorService], never a fixed pool, CS-32); through
 * [BulkheadPolicy]'s semaphore bulkhead; through [CircuitBreakerPolicy]'s
 * breaker; the whole chain retried up to [RetryPolicy.maxAttempts] times with
 * jittered exponential backoff when [policy]'s edge is idempotent — except when
 * the breaker/bulkhead already refused the attempt, which fails fast rather
 * than spending a retry waiting on either.
 *
 * [clock] is supplied by the caller (CS-10); this class never reads the system
 * clock. [close] shuts down the executor this instance owns; call it once, when
 * the owning [Resilience4jResilienceRegistry] itself stops.
 */
class Resilience4jResilientEdge(
    edge: String,
    val policy: EdgePolicy,
    private val clock: Clock,
    private val executorService: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : ResilientEdge,
    AutoCloseable {
    private val timeLimiter = TimeLimiter.of(edge, policy.timeout.toResilience4j())
    private val bulkhead = Bulkhead.of(edge, policy.bulkhead.toResilience4j())
    private val circuitBreaker = CircuitBreaker.of(edge, policy.circuitBreaker.toResilience4j())
    private val retry = policy.retry?.let { Retry.of(edge, it.toResilience4j()) }

    override fun <T> execute(
        deadline: Deadline,
        onCallNotPermitted: () -> T,
        onConnectFailure: () -> T,
        onTimeout: () -> T,
        block: () -> T,
    ): T {
        // INV-REQ-1203 behaviour #3: the budget already ran out before this hop was
        // even attempted — the same outcome as a timeout, without spending an attempt.
        if (deadline.isExpired(clock)) return onTimeout()

        val endUserToken = CurrentEndUserToken.current()
        val boundBlock: Callable<T> = Callable { withCallContext(deadline, endUserToken, block) }

        val timeLimited: Callable<T> =
            Callable { timeLimiter.executeFutureSupplier { executorService.submit(boundBlock) } }
        val bulkheaded: Callable<T> = Bulkhead.decorateCallable(bulkhead, timeLimited)
        val guarded: Callable<T> = CircuitBreaker.decorateCallable(circuitBreaker, bulkheaded)
        val toRun: Callable<T> = retry?.let { Retry.decorateCallable(it, guarded) } ?: guarded

        return try {
            toRun.call()
        } catch (expected: CallNotPermittedException) {
            onCallNotPermitted()
        } catch (expected: BulkheadFullException) {
            onCallNotPermitted()
        } catch (expected: TimeoutException) {
            onTimeout()
        } catch (expected: ConnectException) {
            onConnectFailure()
        } catch (expected: ResourceAccessException) {
            if (expected.cause is ConnectException) onConnectFailure() else throw expected
        }
    }

    override fun close() {
        executorService.shutdown()
    }

    private fun <T> withCallContext(
        deadline: Deadline,
        endUserToken: String?,
        block: () -> T,
    ): T =
        if (endUserToken != null) {
            ScopedValue
                .where(
                    CurrentDeadline.key,
                    deadline,
                ).where(CurrentEndUserToken.key, endUserToken)
                .call<T, RuntimeException>(block)
        } else {
            CurrentDeadline.callWith(deadline, block)
        }
}

// Public, not private: the mapping from this module's framework-free policy
// types to Resilience4j's own config types is what a property test (CS-38)
// checks against arbitrary policies, deterministically and without depending on
// wall-clock timing (src/implTest's `ResiliencePolicyMappingPropertiesTest`) —
// the same shape `adapter-runtime`'s own mapping functions use.

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

// The breaker and the bulkhead already refuse a call fast when open/full;
// retrying either outcome would only spend the bounded attempt budget waiting
// on a state that will not change sooner for it, so both are the exceptions
// retry ignores.
fun RetryPolicy.toResilience4j(): RetryConfig =
    RetryConfig
        .custom<Any>()
        .maxAttempts(maxAttempts)
        .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(initialBackoff, 2.0, jitter, maxBackoff))
        .ignoreExceptions(CallNotPermittedException::class.java, BulkheadFullException::class.java)
        .build()
