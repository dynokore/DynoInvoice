package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.SqlErrors
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import java.sql.SQLTransientConnectionException

/** The switch of a fixture outage, shared by every [OutageTransactionRunner] of a harness. */
class EventLogOutage {
    @Volatile
    var available: Boolean = true
}

/**
 * Failure injection for the event log (owner ruling INV-Q-4508 (A)): a [TransactionRunner] that, while [outage] is not
 * available, refuses to open a transaction with the SQLSTATE 08001 the pool gives for an unreachable server, and
 * otherwise delegates to [delegate]. It takes no connection itself; the SQLSTATE is read through [delegate].
 */
class OutageTransactionRunner(
    private val delegate: TransactionRunnerImpl,
    private val outage: EventLogOutage,
) : TransactionRunner,
    CurrentConnection by delegate,
    SqlErrors by delegate {
    override fun <T> inTransaction(block: () -> T): T {
        if (!outage.available) throw SQLTransientConnectionException("event log unavailable (fixture)", "08001")
        return delegate.inTransaction(block)
    }

    override fun <T> outsideOpenTransaction(block: () -> T): T = delegate.outsideOpenTransaction(block)
}
