package com.dynokore.platform.serviceruntime.pool

import com.dynokore.platform.tenancykernel.LaneGate
import com.dynokore.platform.tenancykernel.LanePermit
import com.dynokore.platform.tenancykernel.LaneRefusedException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The service's one application pool split into lanes (INV-REQ-1230 b1, INV-REQ-1072 b5, INV-ADR-3968 item 1) as a plain
 * permit class (owner ruling INV-Q-4502 (A)): tenancy-kernel's `TenantAwareDataSource` takes a permit of the current
 * thread's lane ([PoolLanes]) before it takes a pool connection and gives it back once when the connection is handed
 * back. The pool maximum is the sum of the budgets, so a lane whose permits are all in use waits for its own permits
 * and never takes a connection another lane is owed. A lane with a budget of 0 is refused, never served from another
 * lane; a saturated lane times out after [timeoutMillis], the pool's connection timeout.
 *
 * No JDBC type is held here; the refusals are [LaneRefusedException]s with a message of the lane and numbers only.
 */
class LanePermits(
    val budgets: LaneBudgets,
    private val timeoutMillis: Long,
) : LaneGate {
    private val permits: Map<PoolLane, Semaphore> = PoolLane.entries.associateWith { Semaphore(budgets.of(it), true) }

    override fun acquire(): LanePermit {
        val lane = PoolLanes.current()
        val budget = budgets.of(lane)
        if (budget == 0) {
            throw LaneRefusedException(
                "the $lane lane has no permits (budget 0) and never runs on another lane (INV-REQ-1072 b5)",
            )
        }
        val semaphore = permits.getValue(lane)
        val acquired =
            try {
                semaphore.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw LaneRefusedException("interrupted while waiting for a $lane lane permit", true, interrupted)
            }
        if (!acquired) {
            throw LaneRefusedException("the $lane lane's $budget permits stayed in use for $timeoutMillis ms")
        }
        val released = AtomicBoolean(false)
        return LanePermit { if (released.compareAndSet(false, true)) semaphore.release() }
    }

    /** The permits of [lane] not in use. */
    fun availablePermits(lane: PoolLane): Int = permits.getValue(lane).availablePermits()

    override fun toString(): String = "LanePermits(request / background / reprocessing $budgets)"
}

/**
 * The lane gate bean (owner ruling INV-Q-4502 (A)): [PoolLanePostProcessor] sets the [LanePermits] of the application pool
 * when it sizes that pool; tenancy-kernel's `TenantAwareDataSource` takes permits from this bean. Without an application
 * Hikari pool (a test double) it is [active] false and admits every connection.
 */
class LaneGateHolder : LaneGate {
    @Volatile
    private var current: LanePermits? = null
    private val observers = CopyOnWriteArrayList<PermitObserver>()

    /** Registers [observer] for every permit this gate hands out (a test hook; none is registered in production). */
    fun observe(observer: PermitObserver) {
        observers += observer
    }

    /** Whether an application pool has been sized to lanes. */
    val active: Boolean get() = current != null

    internal fun set(permits: LanePermits) {
        current = permits
    }

    /** The lane permits of the application pool. */
    fun permits(): LanePermits = checkNotNull(current) { "the application pool has no lanes" }

    override fun acquire(): LanePermit = observed(observers, current?.acquire() ?: LanePermit { })
}

/**
 * A listener on the permits of a gate (coordinator ruling INV-Q-5251): [acquired] runs on the thread that took the
 * permit, [released] on whichever thread gives it back, both with the name of the acquiring thread. No JDBC type.
 */
interface PermitObserver {
    fun acquired(threadName: String)

    fun released(threadName: String)
}

/**
 * The gate of the HA event-log pool: it admits every connection (the pool maximum, 3, still bounds the pool, INV-Q-4348),
 * and accepts [PermitObserver]s like [LaneGateHolder].
 */
class EventLogGate : LaneGate {
    private val observers = CopyOnWriteArrayList<PermitObserver>()

    /** Registers [observer] for every permit this gate hands out (a test hook; none is registered in production). */
    fun observe(observer: PermitObserver) {
        observers += observer
    }

    override fun acquire(): LanePermit = observed(observers, LanePermit { })
}

private fun observed(
    observers: List<PermitObserver>,
    permit: LanePermit,
): LanePermit {
    if (observers.isEmpty()) return permit
    val thread = Thread.currentThread().name
    observers.forEach { it.acquired(thread) }
    val released = AtomicBoolean(false)
    return LanePermit {
        if (released.compareAndSet(false, true)) {
            try {
                permit.release()
            } finally {
                observers.forEach { it.released(thread) }
            }
        }
    }
}
