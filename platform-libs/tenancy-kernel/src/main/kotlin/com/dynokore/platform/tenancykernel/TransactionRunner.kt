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
}
