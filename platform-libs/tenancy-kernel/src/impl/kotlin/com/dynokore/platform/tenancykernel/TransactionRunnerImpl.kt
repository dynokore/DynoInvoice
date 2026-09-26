package com.dynokore.platform.tenancykernel

import java.sql.Connection
import javax.sql.DataSource

/**
 * [TransactionRunner] over a plain [DataSource] (CS-19; no ORM/framework
 * assumed — a service's own `-adapters` module supplies whatever pooled
 * `DataSource` it wires, wrapped in [TenantAwareDataSource]).
 *
 * Binds the session tenant as the very first statement of the transaction
 * (ARC-21: `set_config('app.tenant_id', :tenant, true)`, local scope — CS-19
 * requires transaction scope, never session scope, so the setting cannot
 * survive past this one transaction even before the connection is returned to
 * the pool). REQUIRED propagation: a call nested inside an outer
 * `inTransaction` joins the outer transaction and connection rather than
 * opening a second one.
 */
class TransactionRunnerImpl(
    private val dataSource: DataSource,
    private val tenants: TenantContext,
) : TransactionRunner,
    CurrentConnection {
    private val boundConnection = ThreadLocal<Connection>()

    override fun <T> inTransaction(block: () -> T): T {
        boundConnection.get()?.let { return block() } // REQUIRED propagation: join the outer transaction

        val tenant = tenants.current()
        val connection = dataSource.connection
        try {
            connection.autoCommit = false
            connection.prepareStatement("select set_config('app.tenant_id', ?, true)").use { statement ->
                statement.setString(1, tenant.toString())
                statement.execute()
            }
            boundConnection.set(connection)

            val result =
                try {
                    block()
                } catch (failure: Throwable) {
                    connection.rollback()
                    throw failure
                }
            connection.commit()
            return result
        } finally {
            boundConnection.remove()
            connection.close() // TenantAwareDataSource: RESET app.tenant_id, then back to the pool
        }
    }

    override fun require(): Connection = boundConnection.get()
        ?: throw IllegalStateException(
            "No transaction is open on this thread (ARC-21): repository calls must run inside " +
                "TransactionRunner.inTransaction.",
        )
}
