package com.dynokore.platform.serviceruntime.datasource

import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

/**
 * The HikariCP pool of the Flyway migrate login (coordinator ruling INV-Q-5111; STK-29, INV-Q-3909 (A)): a lazy pool
 * of at most [MAXIMUM] connections whose credentials come only from a [SecretStoreCredentialsProvider], so the password
 * is read through SecretStore for each new physical connection. It sets no username, password, init SQL, schema or
 * data-source property. The maximum is 3, not the 2 of the ruling: a Flyway run with the `afterMigrate` callback holds
 * three connections at once (the pool of 2 timed out in MigrationRunnerIT; reported in the MR).
 */
class FlywayLoginPool : AutoCloseable {
    private var pool: HikariDataSource? = null

    /** Creates the lazy pool for [url] with [credentials] (the only place the credentials provider is set). */
    fun build(
        url: String,
        credentials: SecretStoreCredentialsProvider,
    ): FlywayLoginPool {
        val built = HikariDataSource()
        built.jdbcUrl = url
        built.maximumPoolSize = MAXIMUM
        built.minimumIdle = 0
        built.poolName = "flyway-login"
        built.credentialsProvider = credentials
        pool = built
        return this
    }

    /** The pool, for `FluentConfiguration.dataSource`. */
    fun dataSource(): DataSource = checkNotNull(pool) { "the pool is not built" }

    override fun close() {
        pool?.close()
    }

    companion object {
        const val MAXIMUM = 3
    }
}
