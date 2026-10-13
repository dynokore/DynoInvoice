package com.dynokore.platform.serviceruntime.pool

import com.zaxxer.hikari.HikariDataSource
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * The service's one application pool split into lanes (INV-REQ-1230 b1, INV-REQ-1072 b5, INV-ADR-3968 item 1): a
 * connection is taken in the current thread's lane ([PoolLanes]) and only within that lane's permit budget. The pool
 * maximum is the sum of the budgets, so a lane whose permits are all in use waits for its own permits and never takes
 * a connection another lane is owed: a saturated background or reprocessing lane never blocks a request. A lane with a
 * budget of 0 is refused, never served from another lane.
 *
 * Each connection carries its lane's `application_name` ([applicationNames]), set through pgJDBC's `ApplicationName`
 * client info when the connection is taken (pgJDBC sends it only when it changes), so pg_stat_activity shows a
 * connection's lane.
 */
class LanedDataSource(
    private val pool: HikariDataSource,
    val budgets: LaneBudgets,
    private val applicationNames: Map<PoolLane, String>,
) : DataSource,
    AutoCloseable {
    private val permits: Map<PoolLane, Semaphore> = PoolLane.entries.associateWith { Semaphore(budgets.of(it), true) }

    init {
        check(pool.maximumPoolSize == budgets.maximum) { "the pool maximum ${pool.maximumPoolSize} must be the sum of the lane budgets $budgets" }
    }

    override fun getConnection(): Connection {
        val lane = PoolLanes.current()
        val budget = budgets.of(lane)
        if (budget == 0) {
            throw SQLTransientConnectionException("the $lane lane has no permits (budget 0) and never runs on another lane (INV-REQ-1072 b5)")
        }
        val semaphore = permits.getValue(lane)
        val acquired =
            try {
                semaphore.tryAcquire(pool.connectionTimeout, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw SQLException("interrupted while waiting for a $lane lane permit", interrupted)
            }
        if (!acquired) {
            throw SQLTransientConnectionException("the $lane lane's $budget permits stayed in use for ${pool.connectionTimeout} ms")
        }
        try {
            val connection = pool.connection
            try {
                connection.setClientInfo(APPLICATION_NAME, applicationNames.getValue(lane))
            } catch (failure: SQLException) {
                connection.close()
                throw failure
            }
            return LaneConnection(connection, semaphore)
        } catch (failure: Throwable) {
            semaphore.release()
            throw failure
        }
    }

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = throw UnsupportedOperationException("the application pool's lanes take connections as the runtime login only")

    /** The permits of [lane] not in use. */
    fun availablePermits(lane: PoolLane): Int = permits.getValue(lane).availablePermits()

    override fun getLogWriter(): PrintWriter? = pool.logWriter

    override fun setLogWriter(out: PrintWriter?) {
        pool.logWriter = out
    }

    override fun setLoginTimeout(seconds: Int) {
        pool.loginTimeout = seconds
    }

    override fun getLoginTimeout(): Int = pool.loginTimeout

    override fun getParentLogger(): Logger = pool.parentLogger

    /**
     * The pool for any type it is (Spring Boot's DataSourceBuilder.derivedFrom and its pool metrics unwrap to it);
     * this lane view only for [LanedDataSource] itself.
     */
    override fun <T : Any> unwrap(iface: Class<T>): T = when {
        iface.isInstance(pool) -> iface.cast(pool)
        iface.isInstance(this) -> iface.cast(this)
        else -> pool.unwrap(iface)
    }

    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(pool) || iface.isInstance(this) || pool.isWrapperFor(iface)

    /** Closes the pool (the bean's destroy method). */
    override fun close() = pool.close()

    override fun toString(): String = "LanedDataSource(${pool.poolName}, request / background / reprocessing $budgets)"

    private companion object {
        /** pgJDBC's client-info name for `application_name`. */
        const val APPLICATION_NAME = "ApplicationName"
    }
}

/** A pooled connection that gives its lane permit back once, when it is closed. */
internal class LaneConnection(
    private val delegate: Connection,
    private val permit: Semaphore,
) : Connection by delegate {
    private val released = AtomicBoolean(false)

    override fun close() {
        try {
            delegate.close()
        } finally {
            if (released.compareAndSet(false, true)) permit.release()
        }
    }

    override fun <T : Any> unwrap(iface: Class<T>): T = if (iface.isInstance(this)) iface.cast(this) else delegate.unwrap(iface)

    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this) || delegate.isWrapperFor(iface)
}
