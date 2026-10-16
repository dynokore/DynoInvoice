package com.dynokore.platform.tenancykernel

import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

/**
 * The one test pool fixture (owner ruling INV-Q-4508 (A)): it constructs a HikariCP pool from a Testcontainers URL and
 * the login's name and password, wraps it in [TenantAwareDataSource] (optionally with a [LaneGate]) and hands it to a
 * [TransactionRunnerImpl]. It takes no connection and issues no statement; tests reach the database only through the
 * runner (and jOOQ on top of it). The pool is lazy: no connection before the first transaction.
 *
 * @param maximumPoolSize the pool maximum.
 * @param minimumIdle idle connections the pool keeps.
 * @param connectionTimeoutMillis how long a transaction waits for a pooled connection.
 */
class TestPoolFixture(
    url: String,
    user: String,
    password: String,
    maximumPoolSize: Int = DEFAULT_MAXIMUM,
    minimumIdle: Int = 0,
    connectionTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    poolName: String = "test-pool",
) : AutoCloseable {
    private val pool = HikariDataSource()

    init {
        pool.jdbcUrl = url
        pool.username = user
        pool.password = password
        pool.maximumPoolSize = maximumPoolSize
        pool.minimumIdle = minimumIdle
        pool.connectionTimeout = connectionTimeoutMillis
        pool.poolName = poolName
    }

    /** A runner on this pool; [lanes] gates the connections and [label] names each transaction (INV-Q-4487). */
    @JvmOverloads
    fun runner(
        tenants: TenantContext,
        lanes: LaneGate? = null,
        label: ConnectionLabel? = null,
    ): TransactionRunnerImpl = TransactionRunnerImpl(TenantAwareDataSource(pool, lanes), tenants, label)

    /** The pool as a `DataSource` (gated by [lanes]), for a Spring context that needs the bean; tests take no connection from it. */
    @JvmOverloads
    fun dataSource(lanes: LaneGate? = null): DataSource = TenantAwareDataSource(pool, lanes)

    override fun close() = pool.close()

    companion object {
        const val DEFAULT_MAXIMUM = 4
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
    }
}
