package com.dynokore.platform.outboxandqueue

import java.time.Clock
import java.time.Instant

/**
 * One fixed-window counter per lane, driven by an injected [Clock] (so tests are deterministic). The lanes
 * share nothing: [tryAcquire] on one lane never reads or changes another's window. The count is per worker
 * process; a service that runs several workers divides the limit between them.
 */
class LaneRateLimiter(
    private val limits: LaneLimits,
    private val clock: Clock,
) {
    private class Window(
        var start: Instant,
        var used: Int,
    )

    private val windows = HashMap<Lane, Window>()

    /** Whether [lane] has a permit left now, without taking it. */
    @Synchronized
    fun hasPermit(lane: Lane): Boolean {
        val limit = limits.of(lane) ?: return true
        return current(lane, limit).used < limit.permits
    }

    /** Takes one permit of [lane]; false when its window is used up. */
    @Synchronized
    fun tryAcquire(lane: Lane): Boolean {
        val limit = limits.of(lane) ?: return true
        val window = current(lane, limit)
        if (window.used >= limit.permits) return false
        window.used++
        return true
    }

    /** Gives back a permit taken by [tryAcquire] that turned out to have no work behind it. */
    @Synchronized
    fun refund(lane: Lane) {
        val limit = limits.of(lane) ?: return
        val window = current(lane, limit)
        if (window.used > 0) window.used--
    }

    /** Permits taken from [lane] in its current window (0 for an unlimited lane). */
    @Synchronized
    fun used(lane: Lane): Int = limits.of(lane)?.let { current(lane, it).used } ?: 0

    private fun current(
        lane: Lane,
        limit: RateLimit,
    ): Window {
        val now = clock.instant()
        val window = windows.getOrPut(lane) { Window(now, 0) }
        if (!now.isBefore(window.start.plus(limit.window))) {
            window.start = now
            window.used = 0
        }
        return window
    }
}
