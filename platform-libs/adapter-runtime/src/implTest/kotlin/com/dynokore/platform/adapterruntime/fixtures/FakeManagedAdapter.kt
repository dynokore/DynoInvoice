package com.dynokore.platform.adapterruntime.fixtures

import com.dynokore.platform.adapterruntime.AdapterState
import com.dynokore.platform.adapterruntime.AdapterVersion
import com.dynokore.platform.adapterruntime.ManagedAdapter
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A test fixture for [ManagedAdapter] (CS-43). [beginWork] and [finishWork] let a
 * test simulate one unit of in-flight work, so it can assert that
 * [drainAndStop] really does wait for it (INV-REQ-1354) rather than merely changing
 * state.
 */
class FakeManagedAdapter(override val version: AdapterVersion) : ManagedAdapter {
    @Volatile
    override var state: AdapterState = AdapterState.STARTING
        private set

    private val lock = ReentrantLock()
    private val noActiveCalls = lock.newCondition()
    private var activeCalls = 0

    override fun start() {
        state = AdapterState.RUNNING
    }

    /** Marks one unit of work as accepted; call [finishWork] when it completes. */
    fun beginWork() = lock.withLock {
        activeCalls += 1
    }

    fun finishWork() = lock.withLock {
        activeCalls -= 1
        noActiveCalls.signalAll()
    }

    override fun drainAndStop(deadline: Instant) {
        if (state != AdapterState.STOPPED) state = AdapterState.DRAINING
        lock.withLock {
            while (activeCalls > 0) {
                val remaining = Duration.between(Instant.now(), deadline)
                if (remaining.isNegative || remaining.isZero) break
                noActiveCalls.await(remaining.toMillis().coerceAtLeast(1), TimeUnit.MILLISECONDS)
            }
        }
        state = AdapterState.STOPPED
    }
}
