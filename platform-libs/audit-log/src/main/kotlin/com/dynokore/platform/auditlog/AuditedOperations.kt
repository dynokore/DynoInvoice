package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.EventContext

/**
 * Runs an operation and its audit entry as one unit (INV-REQ-1226, INV-REQ-2588). The caller has bound
 * the tenant (ARC-21). If the entry cannot be written the transaction rolls back and [AuditWriteFailed]
 * is thrown, so a state change commits nothing and a read returns nothing (INV-AC-2593).
 */
interface AuditedOperations {
    /** Runs [block], which changes state, and writes [entry] in the same transaction. */
    fun <T> change(
        entry: AuditEntry,
        context: EventContext,
        block: () -> T,
    ): T

    /**
     * Runs [block], which changes no state, and writes [entry] before the result returns: the result
     * is handed back only once the entry is written.
     */
    fun <T> read(
        entry: AuditEntry,
        context: EventContext,
        block: () -> T,
    ): T
}
