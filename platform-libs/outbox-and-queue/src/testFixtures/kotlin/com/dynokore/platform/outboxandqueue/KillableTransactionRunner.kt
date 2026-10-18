package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.sql.Connection
import javax.sql.DataSource

/**
 * A tenancy-kernel [TransactionRunner] (tenant set as the first statement, ARC-21) whose process
 * can be "killed" after the block has run and before the commit (INV-AC-2205): the connection is
 * closed with the transaction open, as a dead process's would be, so PostgreSQL rolls it back.
 * Not a simulated rollback call.
 */
class KillableTransactionRunner(
    private val dataSource: DataSource,
    val tenants: TenantContext,
) : TransactionRunner,
    CurrentConnection {
    private val bound = ThreadLocal<Connection>()

    @Volatile
    private var killBeforeCommit = false

    /** The open transaction dies after its block, before its commit; call it inside the block. */
    fun armKill() {
        killBeforeCommit = true
    }

    override fun <T> inTransaction(block: () -> T): T {
        if (bound.get() != null) return block()
        val tenant = tenants.current()
        val connection = dataSource.connection
        connection.autoCommit = false
        SigningFixture.bind(connection, tenant.value, tenants.currentActor() ?: OutboxPostgresFixture.WORKLOAD)
        bound.set(connection)
        try {
            val result = block()
            if (killBeforeCommit) {
                killBeforeCommit = false
                connection.close() // abandoned mid-transaction: never committed
                throw ProcessKilled()
            }
            connection.commit()
            return result
        } finally {
            bound.remove()
            if (!connection.isClosed) {
                connection.rollback()
                connection.close()
            }
        }
    }

    /** The seam of tenancy-kernel's `TransactionRunner` (INV-T-5675): the thread's open transaction is hidden for [block]. */
    override fun <T> outsideOpenTransaction(block: () -> T): T {
        val saved = bound.get()
        bound.remove()
        try {
            return block()
        } finally {
            if (saved != null) bound.set(saved) else bound.remove()
        }
    }

    override fun require(): Connection = bound.get() ?: error("no transaction is open on this thread")
}
