package com.dynokore.platform.serviceruntime.pool

/**
 * The lanes of the service's one application pool (INV-REQ-1230 b1, INV-REQ-1072 b5, INV-ADR-3968 item 1; owner
 * ruling INV-Q-4023). Each lane takes connections only within its own permit budget, and the pool maximum is the sum
 * of the budgets, so a lane that has used up its permits never takes a connection another lane is owed.
 */
enum class PoolLane {
    /** Request handling: the lane of every thread that has not entered another one. */
    REQUEST,

    /** Background work: relay, consumers, claims, jobs and purges. */
    BACKGROUND,

    /** Pack-activation reprocessing and backfill; never runs on a live lane (INV-REQ-1072 b5). */
    REPROCESSING,
}

/**
 * The lane of the current thread. Connections taken from the application DataSource while [inLane] runs count
 * against that lane's permits; outside any [inLane] a thread is in [PoolLane.REQUEST].
 */
object PoolLanes {
    private val current = ThreadLocal<PoolLane>()

    /** The current thread's lane. */
    fun current(): PoolLane = current.get() ?: PoolLane.REQUEST

    /** Runs [block] in [lane], then restores the previous lane. */
    fun <T> inLane(
        lane: PoolLane,
        block: () -> T,
    ): T {
        val previous = current.get()
        current.set(lane)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
