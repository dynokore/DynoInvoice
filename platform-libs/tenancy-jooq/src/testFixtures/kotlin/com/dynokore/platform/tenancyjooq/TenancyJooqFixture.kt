package com.dynokore.platform.tenancyjooq

import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.PostgresProbeFixture
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.flywaydb.core.Flyway
import org.jooq.Configuration
import org.jooq.SQLDialect
import org.jooq.impl.DefaultConfiguration
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.Collections
import javax.sql.DataSource

/**
 * tenancy-jooq's own test harness (test fixtures, so its tests name no JDBC type; owner ruling INV-Q-4196): a
 * PostgreSQL [image] with tenancy-kernel's owner and runtime roles, the [migrations] applied by Flyway as the owner,
 * tenancy-kernel's tenant context and transaction runner on the runtime login, a [TenancyProbe] on the same login, and
 * a log of every statement the runtime login's connections prepare or execute.
 */
internal class TenancyJooqFixture(
    image: String,
    vararg migrations: String,
) : AutoCloseable {
    private val postgres = PostgresProbeFixture(image)
    private val statements: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val runnerImpl: TransactionRunnerImpl
    private val contextImpl = TenantContextImpl()

    /** The tenant context the runner and the listeners read. */
    val tenants: TenantContext get() = contextImpl

    /** The platform-tenant binding of the same context. */
    val platform: PlatformTenantBinding get() = contextImpl

    /** tenancy-kernel's transaction runner on the runtime login (`tenancy_kernel_app`). */
    val transactions: TransactionRunner get() = runnerImpl

    /** A probe on the runtime login. */
    val probe: TenancyProbe

    init {
        Flyway
            .configure()
            .dataSource(postgres.ownerDataSource())
            .schemas("tenancy_kernel_test")
            .createSchemas(false)
            .table("tenancy_jooq_test_history")
            .locations(*migrations)
            .load()
            .migrate()
        runnerImpl = TransactionRunnerImpl(logging(postgres.appDataSource()), contextImpl)
        probe = TenancyProbe.over(postgres.appDataSource())
    }

    /** A PostgreSQL configuration with tenancy-jooq installed for [tables]. */
    fun configuration(tables: TenancyTables): Configuration {
        val base = DefaultConfiguration().set(SQLDialect.POSTGRES)
        return TenancyJooq.configure(base, tables, tenants, transactions)
    }

    /** A probe on the container's superuser, which [TenancyProbe] must refuse. */
    fun superuserProbe(): TenancyProbe = TenancyProbe.connect(postgres.jdbcUrl(), "test", "test")

    /** The statements the runtime login's connections prepared or executed so far, in order. */
    fun statementLog(): List<String> = synchronized(statements) { statements.toList() }

    /** The statements logged after [mark] (a size of [statementLog]), except the runner's own binding statement. */
    fun statementsSince(mark: Int): List<String> {
        val since = statementLog().drop(mark)
        return since.filterNot { it.startsWith("select set_config(") }
    }

    override fun close() {
        postgres.close()
    }

    private fun logging(dataSource: DataSource): DataSource {
        val handler =
            InvocationHandler { _, method, args ->
                val result = invoke(dataSource, method, args)
                if (method.name == "getConnection") logging(result as Connection) else result
            }
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DataSource::class.java), handler) as DataSource
    }

    private fun logging(connection: Connection): Connection {
        val handler =
            InvocationHandler { _, method, args ->
                if (method.name in LOGGED && args?.firstOrNull() is String) statements += args[0] as String
                invoke(connection, method, args)
            }
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java), handler) as Connection
    }

    private fun invoke(
        target: Any,
        method: java.lang.reflect.Method,
        args: Array<out Any?>?,
    ): Any? =
        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }

    private companion object {
        val LOGGED = setOf("prepareStatement", "prepareCall", "nativeSQL")
    }
}
