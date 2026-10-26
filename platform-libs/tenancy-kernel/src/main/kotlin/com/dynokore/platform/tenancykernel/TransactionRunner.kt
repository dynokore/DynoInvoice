package com.dynokore.platform.tenancykernel

/**
 * Runs [block] in one database transaction, with the binding's tenant and actor set
 * as its first statement (ARC-21 as revised: `set_config('app.tenant_id', :tenant, true)`
 * and `set_config('app.actor', :actor, true)`, scoped `true`: local to the transaction,
 * never the session, CS-19; owner rulings INV-Q-3951, INV-Q-3953; INV-REQ-854 b1).
 * Every transaction carries an actor: a binding with none is refused with
 * `IllegalStateException` before any statement (INV-Q-3997, INV-T-3999).
 *
 * REQUIRED propagation: a nested `inTransaction` call joins the same
 * transaction rather than opening a new one, but only when the current binding
 * (tenant and actor) equals the open transaction's; otherwise it is refused with
 * `IllegalStateException` before any statement, never run under the outer
 * tenant (INV-Q-3997). No repository call runs outside an `inTransaction` block
 * (ARC-21) — there is no ambient connection to use.
 *
 * The tenant bound is [TenantContext.current] at the moment `inTransaction` is
 * entered; there is no ambient default, so a caller with nothing bound gets
 * `TenantContext.current`'s `IllegalStateException` before any SQL runs.
 *
 * Framework-free (ARC-16): this is the `-api` half of the module.
 */
interface TransactionRunner {
    fun <T> inTransaction(block: () -> T): T

    /**
     * Runs [block] with the calling thread's open-transaction marker of this runner cleared, and restores it after,
     * also when [block] throws (owner answer INV-Q-5609 (b)). A transaction opened inside [block] takes its own
     * connection and its own binding, so a run-spanning transaction (an advisory lock) and per-row transactions under
     * other tenants can coexist on one thread. The outer transaction stays open and is neither committed nor rolled
     * back by the block. Only the calling thread's marker of this runner is cleared. With no transaction open it just
     * runs [block].
     *
     * A runner without an override clears nothing: the default body runs [block] inside the open transaction, so a
     * different-binding transaction there is still refused (INV-Q-3997). A seam user needs at least 2 permits in its
     * lane, because the outer and the inner connection each take one [LaneGate] permit (ruling INV-Q-5683); with
     * fewer, the inner checkout is refused with [LaneRefusedException].
     */
    fun <T> outsideOpenTransaction(block: () -> T): T = block()

    /**
     * Registers [action] to run once after the outermost transaction of the calling thread has committed and its
     * connection has been closed (INV-T-11066, INV-Q-11056). Actions run in registration order on the thread that
     * called the outermost [inTransaction]. If the block or the commit throws, no action runs. An action that throws
     * an `Exception` does not stop the later actions and does not change what [inTransaction] returns or throws.
     * With no transaction open on the calling thread, also inside [outsideOpenTransaction], it throws
     * `IllegalStateException` and registers nothing.
     *
     * A runner that cannot honour the hook must not run the action at once or drop it: the default body throws
     * `UnsupportedOperationException` naming the runner's class.
     */
    fun afterCommit(action: () -> Unit): Unit =
        throw UnsupportedOperationException(
            "${javaClass.name} does not support afterCommit: it cannot run an action after the commit (INV-Q-11056).",
        )
}
