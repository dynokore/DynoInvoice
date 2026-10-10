package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.tenancykernel.TransactionRunner

/**
 * [AuditedOperations] over tenancy-kernel's [TransactionRunner]: the block and the entry share one
 * transaction, so a failed entry rolls the block back (INV-AC-2593). The result is returned only after
 * the transaction commits, so a read whose entry failed returns nothing.
 */
class TransactionalAuditedOperations(
    private val tx: TransactionRunner,
    private val audit: AuditWriter,
) : AuditedOperations {
    override fun <T> change(
        entry: AuditEntry,
        context: EventContext,
        block: () -> T,
    ): T = run(entry, context, block)

    override fun <T> read(
        entry: AuditEntry,
        context: EventContext,
        block: () -> T,
    ): T = run(entry, context, block)

    private fun <T> run(
        entry: AuditEntry,
        context: EventContext,
        block: () -> T,
    ): T = tx.inTransaction {
        val result = block()
        audit.record(entry, context)
        result
    }
}
