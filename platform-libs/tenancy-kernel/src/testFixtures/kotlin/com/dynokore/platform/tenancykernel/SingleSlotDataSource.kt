package com.dynokore.platform.tenancykernel

import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import javax.sql.DataSource

/**
 * A pool of exactly one physical connection, reused across every `close()` —
 * ARC-21's own named CI check for the leaked-context defect: "integration
 * test on a pool of size 1 alternating tenants". A real pool (HikariCP,
 * whatever a service wires) reuses physical connections across logical
 * checkouts the same way; this is the smallest fixture that reproduces that
 * behaviour deterministically, without a real pooling library as a test
 * dependency.
 *
 * `close()` on the [Connection] this hands out never closes the physical
 * connection — it only frees the slot, so the *next* `getConnection()` is
 * provably the same physical connection, letting a test observe whether
 * anything the previous logical use left behind (a session-level `SET`, an
 * uncommitted transaction) survived the handback.
 */
class SingleSlotDataSource(
    url: String,
    user: String,
    password: String,
) : DataSource,
    AutoCloseable {
    private val physical: Connection =
        PGSimpleDataSource()
            .apply {
                setUrl(url)
                this.user = user
                this.password = password
            }.connection

    @Volatile private var checkedOut = false

    /** How many connections were checked out, both `getConnection` forms (INV-Q-5208: replaces a counting wrapper). */
    @Volatile
    var checkouts = 0
        private set

    /** This instance: the class is the [DataSource] (INV-Q-5153). */
    val dataSource: DataSource get() = this

    override fun getConnection(): Connection = checkOut()

    override fun getConnection(
        username: String,
        password: String,
    ): Connection = checkOut()

    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()

    override fun isWrapperFor(iface: Class<*>?): Boolean = false

    override fun getLogWriter() = null

    override fun setLogWriter(out: java.io.PrintWriter?) = Unit

    override fun setLoginTimeout(seconds: Int) = Unit

    override fun getLoginTimeout(): Int = 0

    override fun getParentLogger(): java.util.logging.Logger = throw UnsupportedOperationException()

    private fun checkOut(): Connection {
        check(!checkedOut) { "SingleSlotDataSource: the one slot is already checked out (test bug, not a pool defect)" }
        checkedOut = true
        checkouts++
        return SlotConnection()
    }

    override fun close() {
        physical.close()
    }

    /** The physical connection by delegation; only `close()` differs: it frees the slot and never closes it. */
    private inner class SlotConnection : Connection by physical {
        override fun close() {
            checkedOut = false
        }
    }
}
