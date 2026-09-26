package com.dynokore.platform.tenancykernel

/**
 * Runs [block] in one database transaction, with the session tenant set as its
 * first statement (ARC-21: `select set_config('app.tenant_id', :tenant, true)`,
 * scoped `true` — local to the transaction, never the session, CS-19).
 *
 * REQUIRED propagation: a nested `inTransaction` call joins the same
 * transaction rather than opening a new one. No repository call runs outside
 * an `inTransaction` block (ARC-21) — there is no ambient connection to use.
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
