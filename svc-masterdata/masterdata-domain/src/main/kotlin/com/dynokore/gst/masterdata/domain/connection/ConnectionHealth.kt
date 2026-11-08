package com.dynokore.gst.masterdata.domain.connection

import java.time.Duration
import java.time.Instant

/** The adapter circuit-breaker state; equal to the `breakerState` enum of `ConnectionHealth` in openapi.yaml. */
enum class BreakerState {
    CLOSED,
    HALF_OPEN,
    OPEN,
    ;

    companion object {
        /** Exact, case-sensitive match on the member name; null for any other text, which the caller refuses. */
        fun parse(text: String): BreakerState? = entries.firstOrNull { it.name == text }
    }
}

/** The connection state shown to operators (INV-REQ-836 behavior 4). */
enum class ConnectionState {
    UP,
    DEGRADED,
    DOWN,
    ;

    companion object {
        /** closed = UP, half-open = DEGRADED, open = DOWN; an exhaustive `when`, so a new value cannot go unmapped. */
        fun of(breaker: BreakerState): ConnectionState =
            when (breaker) {
                BreakerState.CLOSED -> UP
                BreakerState.HALF_OPEN -> DEGRADED
                BreakerState.OPEN -> DOWN
            }
    }
}

/** One recorded call of a connection. [failure] is null for a success and the classified result otherwise. */
data class CallRecord(
    val at: Instant,
    val latency: Duration,
    val failure: CallFailure? = null,
) {
    init {
        require(!latency.isNegative) { "a latency is not negative" }
    }

    val succeeded: Boolean get() = failure == null
}

/** A failed call: the class and a short code, never raw error text. */
data class CallFailure(
    val classification: TestClass,
    val detail: String? = null,
) {
    init {
        require(classification != TestClass.SUCCESS) { "a failure is not classified SUCCESS" }
        require(
            detail == null || ConnectionTestResult.DETAIL.matches(detail),
        ) { "the detail is a code, not raw error text" }
    }
}

data class LastFailure(
    val at: Instant,
    val failure: CallFailure,
)

/**
 * Breaker thresholds and the observation window. No property has a default: the values come from configuration
 * (wired by INV-T-18776) and an unset value fails closed at start-up.
 */
data class HealthSettings(
    /** The length of the observation window. */
    val window: Duration,
    /** The number of consecutive failures that opens the breaker. */
    val failureThreshold: Int,
    /** How long the breaker stays open after its last failure before it admits a probe (half-open). */
    val openFor: Duration,
) {
    init {
        require(!window.isNegative && !window.isZero) { "the window is positive" }
        require(failureThreshold >= 1) { "the failure threshold is at least 1" }
        require(!openFor.isNegative && !openFor.isZero) { "the open duration is positive" }
    }
}

/**
 * INV-REQ-836: the health of one connection computed from the calls of one observation window.
 * [successRate] and [latency] are null before the first recorded call of the window, never zero.
 */
data class ConnectionHealth(
    val connectionRef: String,
    val breakerState: BreakerState,
    val successRate: Double?,
    val latency: Duration?,
    val measuredOver: Duration,
    val lastFailure: LastFailure?,
) {
    val state: ConnectionState get() = ConnectionState.of(breakerState)

    companion object {
        /**
         * [windowCalls] are the calls inside the window, oldest first. The breaker opens after
         * [HealthSettings.failureThreshold] consecutive trailing failures; it is half-open once
         * [HealthSettings.openFor] has passed since the last failure, and a later success closes it.
         */
        fun compute(
            connectionRef: String,
            windowCalls: List<CallRecord>,
            lastFailure: LastFailure?,
            now: Instant,
            settings: HealthSettings,
        ): ConnectionHealth {
            val calls = windowCalls.sortedBy { it.at }
            val trailing = calls.takeLastWhile { !it.succeeded }.size
            val breaker =
                when {
                    trailing < settings.failureThreshold -> {
                        BreakerState.CLOSED
                    }

                    now.isBefore(calls.last().at.plus(settings.openFor)) -> {
                        BreakerState.OPEN
                    }

                    else -> {
                        BreakerState.HALF_OPEN
                    }
                }
            val rate = if (calls.isEmpty()) null else calls.count { it.succeeded }.toDouble() / calls.size
            val latency =
                if (calls.isEmpty()) null else Duration.ofNanos(calls.sumOf { it.latency.toNanos() } / calls.size)
            return ConnectionHealth(connectionRef, breaker, rate, latency, settings.window, lastFailure)
        }
    }
}
