package com.dynokore.platform.tenancykernel

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
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
 * This wrapper runs `RESET app.tenant_id` and `RESET app.actor` immediately before that handback, on
 * the pool's own physical connection, using a JDK dynamic proxy: `Connection`
 * has no `by`-delegation-friendly single seam for "run one statement, then
 * delegate everything else, including `close`, unchanged" the way a hand
 * written wrapper would need one override method per interface (~40 methods)
 * to stay a transparent passthrough.
 */
class TenantAwareDataSource(private val delegate: DataSource) : DataSource by delegate {
    override fun getConnection(): Connection = wrap(delegate.connection)

    override fun getConnection(
        username: String,
        password: String,
    ): Connection = wrap(delegate.getConnection(username, password))

    private fun wrap(physical: Connection): Connection {
        val handler =
            InvocationHandler { _, method, args ->
                if (method.name == "close" && (args == null || args.isEmpty())) {
                    resetTenantThenClose(physical)
                    null
                } else {
                    invoke(physical, method, args)
                }
            }
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
            handler,
        ) as Connection
    }

    private fun resetTenantThenClose(physical: Connection) {
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

    private fun invoke(
        target: Connection,
        method: Method,
        args: Array<out Any?>?,
    ): Any? = try {
        if (args == null) method.invoke(target) else method.invoke(target, *args)
    } catch (e: java.lang.reflect.InvocationTargetException) {
        throw e.targetException
    }
}
