package com.dynokore.gst.consoleBff.adapters.session

import java.time.Clock

/** Removes the sessions past either timeout. A request never depends on it: the resolver checks both timeouts itself. */
class SessionSweeper(
    private val store: JooqSessionStore,
    private val clock: Clock,
    private val idle: java.time.Duration,
    private val absolute: java.time.Duration,
) {
    /** Deletes the expired rows and returns how many. */
    fun sweep(): Int = store.deleteExpired(clock.instant(), idle, absolute)
}
