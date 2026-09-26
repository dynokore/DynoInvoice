package com.dynokore.platform.adapterruntime

/**
 * Runs one call under an edge's full [ResiliencePolicy] — timeout, exponential
 * backoff with jitter, bounded retry, circuit breaking and a bulkhead — on
 * Resilience4j core (STK-24, INV-REQ-1353). Every external interface adapter calls
 * out through one of these, never directly.
 *
 * On success, [execute] returns the block's result. On failure it either propagates
 * transparently (circuit open, bulkhead full) or, once its bounded retry is
 * exhausted, sends one [DeadLetterEntry] to its [DeadLetterSink] and rethrows the
 * last failure (CS-14, CS-15: an exception, never a swallowed result). Exceptions are
 * caught and mapped at the calling adapter's own boundary, not here.
 *
 * See [Resilience4jResilientExecutor][com.dynokore.platform.adapterruntime.resilience4j.Resilience4jResilientExecutor]
 * for the one shipped implementation.
 */
interface ResilientExecutor {
    /** The policy this executor enforces on every call. */
    val policy: ResiliencePolicy

    /** Runs [block] under [policy]. */
    fun <T> execute(block: () -> T): T
}
