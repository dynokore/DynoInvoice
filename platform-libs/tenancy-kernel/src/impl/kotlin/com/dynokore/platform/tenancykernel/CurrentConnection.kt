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
}
