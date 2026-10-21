package com.dynokore.gst.gatewayauthz.ratelimit

import java.time.Duration
import java.time.Instant

/**
 * The requests one key (a principal, a tenant or a link) made in the last [window], counted locally on this replica
 * (owner ruling INV-Q-4048 A: no shared counter store). A request is refused when [limit] requests already fall
 * within the window, so the (limit + 1)th request within any window of that length is refused (INV-AC-4052).
 */
class SlidingWindow(
    private val window: Duration,
) {
    private val admitted = ArrayDeque<Instant>()

    /** Requests within the window ending at [now] and the time until the oldest of them leaves it. */
    @Synchronized
    fun usage(now: Instant): Usage {
        expire(now)
        val reset = admitted.firstOrNull()?.let { Duration.between(now, it.plus(window)) } ?: window
        return Usage(admitted.size, reset)
    }

    @Synchronized
    fun record(now: Instant) {
        admitted.addLast(now)
    }

    @Synchronized
    fun isIdle(now: Instant): Boolean {
        expire(now)
        return admitted.isEmpty()
    }

    private fun expire(now: Instant) {
        val horizon = now.minus(window)
        while (admitted.firstOrNull()?.let { !it.isAfter(horizon) } == true) admitted.removeFirst()
    }

    data class Usage(
        val count: Int,
        val reset: Duration,
    )
}
