package com.dynokore.platform.tenancyjooq

/**
 * A statement that tenancy-jooq refuses before any SQL reaches the server (INV-ADR-3968 item 4a, INV-REQ-854 b6):
 * a plain-SQL query part, TRUNCATE or DDL, a table that is not in the declared schema, a tenant table whose injected
 * predicate is missing, a write that would cross the bound tenant, or a statement run without an open transaction or
 * under a binding that differs from the transaction's.
 *
 * An unchecked exception that jOOQ passes through its listeners and execution unchanged. Not a jOOQ
 * `DataAccessException`: its text constructors are on platform-build's plain-SQL deny list (INV-Q-4362).
 */
class TenantPredicateViolation(
    message: String,
    cause: Throwable? = null,
) : RuntimeException("TenantPredicateViolation: $message", cause)
