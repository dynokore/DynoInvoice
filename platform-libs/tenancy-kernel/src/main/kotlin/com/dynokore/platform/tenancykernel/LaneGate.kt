package com.dynokore.platform.tenancykernel

/**
 * The pool lane gate (owner ruling INV-Q-4502 (A); INV-REQ-1230 b1, INV-REQ-1072 b5): [TenantAwareDataSource] takes a
 * permit with [acquire] before it takes a pool connection and releases it once when the connection is handed back.
 * The interface holds no `java.sql` or `javax.sql` type, so the lane budgets live outside the connection core.
 */
interface LaneGate {
    /**
     * Takes a permit of the calling thread's lane, waiting only for that lane's own permits.
     *
     * @throws LaneRefusedException when the lane has no permits, the wait timed out or the thread was interrupted.
     */
    fun acquire(): LanePermit
}

/** A permit taken from a [LaneGate]; [release] gives it back once, a second call does nothing. */
fun interface LanePermit {
    fun release()
}

/**
 * The lane gate refused a connection (INV-Q-5121). The message names the lane and numbers only, never a value, so
 * it may be logged (CS-34). [TenantAwareDataSource] throws it as the cause of a `SQLTransientConnectionException`, or
 * of a plain `SQLException` when [interrupted].
 */
class LaneRefusedException(
    message: String,
    val interrupted: Boolean = false,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
