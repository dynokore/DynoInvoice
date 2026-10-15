package com.dynokore.platform.tenancykernel

import java.sql.Connection

/**
 * The connection bound to the calling thread's in-flight [TransactionRunner]
 * transaction — the seam a service's own repository code (plain JDBC, or
 * jOOQ's `DSL.using(connection)`) uses to run its statements inside that same
 * transaction. Never available outside an `inTransaction` block (ARC-21: "No
 * repository call runs outside `TransactionRunner`").
 */
interface CurrentConnection {
    fun require(): Connection

    /**
     * An opaque token of the transaction open on the calling thread, or null when none is: two reads return the same
     * object exactly while the same transaction is open. It names no JDBC type, so callers outside the connection core
     * can tell whether a transaction is open, and whether it is still the one they saw, without a `java.sql` type.
     */
    fun transactionToken(): Any? =
        try {
            require()
        } catch (_: IllegalStateException) {
            null
        }
}
