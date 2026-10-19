package com.dynokore.platform.tenancykernel

import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/**
 * Wraps a pooled [DataSource] so the session tenant (and `app.actor`, INV-Q-3997) is cleared the moment a
 * connection returns to the pool (INV-REQ-854 behaviour 3), independent of and
 * in addition to [TransactionRunnerImpl]'s own `set_config(…, true)` (CS-19):
 * that setting is already transaction-local and so already gone once the
 * transaction ends, but this is the belt-and-braces the done-when item asks
 * Testcontainers to prove directly — a connection handed back to the pool
 * never carries a leftover tenant, regardless of how it got there (a bug that
 * used session-level `SET`, a transaction that never reached
 * [TransactionRunner], a driver that pools more aggressively than expected).
 *
 * `close()` on the connection the pool hands out does not close the physical
 * connection (the pool intercepts that itself) — it returns it to the pool.
 * [ResettingConnection] runs `RESET app.tenant_id` and `RESET app.actor` immediately before that handback, on
 * the pool's own physical connection; it is a `Connection` by Kotlin delegation that overrides only `close()`
 * (coordinator ruling INV-Q-4504: no reflection).
 *
 * With a [lanes] gate (owner ruling INV-Q-4502 (A)) a permit is taken before the pool connection and released once
 * when the connection is handed back, or at once when no connection could be taken. A refusal is thrown as a
 * `SQLTransientConnectionException` whose cause is the gate's [LaneRefusedException] (a plain `SQLException` when the
 * wait was interrupted), as the former lane wrapper threw it.
 */
class TenantAwareDataSource
    @JvmOverloads
    constructor(
        private val delegate: DataSource,
        private val lanes: LaneGate? = null,
    ) : DataSource by delegate {
        override fun getConnection(): Connection = wrap { delegate.connection }

        override fun getConnection(
            username: String,
            password: String,
        ): Connection = wrap { delegate.getConnection(username, password) }

        private fun wrap(take: () -> Connection): Connection {
            val permit = acquire()
            try {
                return ResettingConnection(take(), permit)
            } catch (expected: Throwable) {
                permit?.release()
                throw expected
            }
        }

        private fun acquire(): LanePermit? =
            try {
                lanes?.acquire()
            } catch (refused: LaneRefusedException) {
                throw if (refused.interrupted) {
                    SQLException(refused.message, refused)
                } else {
                    SQLTransientConnectionException(refused.message, refused)
                }
            }

        private class ResettingConnection(
            private val physical: Connection,
            private val permit: LanePermit?,
        ) : Connection by physical {
            private val closed = AtomicBoolean(false)

            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                try {
                    resetTenantThenClose()
                } finally {
                    permit?.release()
                }
            }

            private fun resetTenantThenClose() {
                try {
                    if (!physical.isClosed) {
                        physical.createStatement().use {
                            it.execute("RESET app.tenant_id")
                            it.execute("RESET app.actor")
                        }
                    }
                } finally {
                    physical.close()
                }
            }
        }
    }
