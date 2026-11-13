package com.dynokore.gst.transport.adapters.ap

import com.dynokore.gst.transport.application.AdapterBreaker
import com.dynokore.gst.transport.application.BreakerState
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The thresholds of the adapter circuit breaker (INV-REQ-1353). They are configuration (`transport.ap.breaker.*`),
 * never defaults in code: [failureThreshold] consecutive failures open the breaker, [openFor] later it lets a probe
 * through (half-open), and [halfOpenSuccesses] consecutive successes in half-open close it again; a failure in
 * half-open opens it at once.
 */
data class BreakerSettings(
    val failureThreshold: Int,
    val openFor: Duration,
    val halfOpenSuccesses: Int,
) {
    init {
        require(failureThreshold >= 1) { "transport.ap.breaker.failure-threshold must be at least 1" }
        require(!openFor.isNegative && !openFor.isZero) { "transport.ap.breaker.open-for must be positive" }
        require(halfOpenSuccesses >= 1) { "transport.ap.breaker.half-open-successes must be at least 1" }
    }
}

/**
 * One circuit breaker per connection (adapter id), in memory. The state machine is the standard one (closed, open,
 * half-open; Nygard, "Release It!"): the AP probe asks [permits] before it calls the AP and reports the outcome to
 * [record], so a probe goes through the breaker of its adapter. A connection never seen is closed.
 */
class AdapterCircuitBreaker(
    private val settings: BreakerSettings,
    private val clock: Clock,
) : AdapterBreaker {
    private class Circuit {
        var state = BreakerState.CLOSED
        var failures = 0
        var successes = 0
        var openedAt: Instant = Instant.EPOCH
    }

    private val circuits = ConcurrentHashMap<String, Circuit>()

    private fun circuit(connectionRef: String) = circuits.computeIfAbsent(connectionRef) { Circuit() }

    /** The state now: an open breaker whose wait has run out is half-open. */
    fun state(connectionRef: String): BreakerState = circuit(connectionRef).let { c -> synchronized(c) { settle(c) } }

    /** False only while the breaker is open and its wait has not run out: the AP is not called. */
    fun permits(connectionRef: String): Boolean = state(connectionRef) != BreakerState.OPEN

    override fun record(
        connectionRef: String,
        success: Boolean,
    ): BreakerState =
        circuit(connectionRef).let { c ->
            synchronized(c) {
                when (settle(c)) {
                    BreakerState.CLOSED -> recordClosed(c, success)
                    BreakerState.HALF_OPEN -> recordHalfOpen(c, success)
                    BreakerState.OPEN -> Unit // not permitted: a refused call is no new evidence
                }
                c.state
            }
        }

    private fun settle(c: Circuit): BreakerState {
        if (c.state == BreakerState.OPEN && !clock.instant().isBefore(c.openedAt.plus(settings.openFor))) {
            c.state = BreakerState.HALF_OPEN
            c.successes = 0
        }
        return c.state
    }

    private fun recordClosed(
        c: Circuit,
        success: Boolean,
    ) {
        if (success) {
            c.failures = 0
        } else if (++c.failures >= settings.failureThreshold) {
            open(c)
        }
    }

    private fun recordHalfOpen(
        c: Circuit,
        success: Boolean,
    ) {
        if (!success) {
            open(c)
        } else if (++c.successes >= settings.halfOpenSuccesses) {
            c.state = BreakerState.CLOSED
            c.failures = 0
        }
    }

    private fun open(c: Circuit) {
        c.state = BreakerState.OPEN
        c.openedAt = clock.instant()
        c.failures = 0
        c.successes = 0
    }
}
