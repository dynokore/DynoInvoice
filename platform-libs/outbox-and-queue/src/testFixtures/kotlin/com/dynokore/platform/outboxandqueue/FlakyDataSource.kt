package com.dynokore.platform.outboxandqueue

import java.sql.Connection
import java.sql.SQLTransientConnectionException
import javax.sql.DataSource

/**
 * Wraps a DataSource and refuses every connection while [available] is false: the event log
 * made unavailable at the connection level (INV-AC-2185), without stopping the container
 * that also holds the producers' own schemas.
 */
class FlakyDataSource(
    private val delegate: DataSource,
) : DataSource by delegate {
    @Volatile
    var available: Boolean = true

    override fun getConnection(): Connection = guarded { delegate.connection }

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = guarded { delegate.getConnection(username, password) }

    private fun guarded(open: () -> Connection): Connection {
        if (!available) throw SQLTransientConnectionException("event log unavailable (fixture)", "08001")
        return open()
    }
}
