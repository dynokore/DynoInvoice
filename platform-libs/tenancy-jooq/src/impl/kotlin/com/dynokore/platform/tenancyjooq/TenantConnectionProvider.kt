package com.dynokore.platform.tenancyjooq

import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.ConnectionProvider
import java.sql.Connection
import java.sql.SQLException

/**
 * The only connection a tenancy-jooq DSLContext uses: tenancy-kernel's current transaction connection, whose
 * tenant and actor settings the transaction runner set. No binding, no open transaction, or a binding that
 * differs from the open transaction's (a nested binding mismatch, INV-Q-3997) is refused.
 */
internal class TenantConnectionProvider(
    private val transactions: TransactionRunner,
    private val currentConnection: CurrentConnection,
) : ConnectionProvider {
    override fun acquire(): Connection {
        val connection =
            try {
                currentConnection.require()
            } catch (none: IllegalStateException) {
                throw TenantPredicateViolation("no transaction is open on this thread", none)
            }
        try {
            // Joins the open transaction only under its own binding; any other binding throws.
            transactions.inTransaction { }
        } catch (mismatch: IllegalStateException) {
            throw TenantPredicateViolation("the bound tenant or actor differs from the open transaction's", mismatch)
        }
        return connection
    }

    @Throws(SQLException::class)
    override fun release(connection: Connection) {
        // The transaction runner owns the connection and closes it when the transaction ends.
    }
}
