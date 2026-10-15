package com.dynokore.platform.tenancykernel

/**
 * Reads the SQLSTATE of a database error without a `java.sql` type in the caller (INV-Q-5121). The transaction runner
 * implements it, so callers that classify errors (the event loop, the consumer rebuild) need no JDBC import.
 */
fun interface SqlErrors {
    /**
     * The first SQLSTATE found by walking the cause chain of [error] to a depth of 8 (a jOOQ `DataAccessException`
     * wraps the `SQLException`), or null when the chain holds none.
     */
    fun sqlStateOf(error: Throwable): String?
}
