package com.dynokore.platform.serviceruntime.testing

import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.LaneGate
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TestPoolFixture
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import javax.sql.DataSource

/**
 * JDBC-free database access for the service-runtime integration tests (owner ruling INV-Q-4508 (A), coordinator ruling
 * INV-Q-5119): a pool for one login from [TestPoolFixture], a transaction runner on it and a DSLContext from
 * tenancy-jooq's factory over the [tables] the test declares. A test reaches the database only through
 * [Access.asTenant] and [Access.asPlatform] and jOOQ; rows of several tenants are read by binding each tenant the test
 * created, and the platform tenant, in turn ([Access.rowsOf]).
 *
 * @param tables `schema.table` to its class (TENANT, or PLATFORM_SCOPED for a table with no tenant column).
 */
class TestDatabase(
    private val url: String,
    tables: Map<String, TableClass>,
) : AutoCloseable {
    val tenants = TenantContextImpl()
    val tenancyTables: TenancyTables =
        TenancyTables.declared(tables.mapKeys { (name, _) -> DSL.name(name.split('.')) })
    private val pools = mutableListOf<TestPoolFixture>()

    /** A pool, runner and DSLContext for [user]; [lanes] and [label] as a service would wire them. */
    @JvmOverloads
    fun login(
        user: String,
        password: String,
        maximumPoolSize: Int = TestPoolFixture.DEFAULT_MAXIMUM,
        lanes: LaneGate? = null,
        connectionTimeoutMillis: Long = TestPoolFixture.DEFAULT_TIMEOUT_MILLIS,
    ): Access {
        val pool = TestPoolFixture(url, user, password, maximumPoolSize, 0, connectionTimeoutMillis, user)
        pools += pool
        val runner = pool.runner(tenants, lanes)
        val dsl =
            TenancyJooq.configure(DefaultConfiguration().set(SQLDialect.POSTGRES), tenancyTables, tenants, runner).dsl()
        return Access(this, runner, dsl, pool.dataSource(lanes))
    }

    /** A runner and DSLContext over a pool the test did not build (a service's own pool, a bean of its context). */
    fun over(dataSource: DataSource): Access {
        val runner = TransactionRunnerImpl(dataSource, tenants)
        val dsl =
            TenancyJooq.configure(DefaultConfiguration().set(SQLDialect.POSTGRES), tenancyTables, tenants, runner).dsl()
        return Access(this, runner, dsl, dataSource)
    }

    override fun close() = pools.forEach { it.close() }

    class Access internal constructor(
        private val db: TestDatabase,
        val runner: TransactionRunnerImpl,
        val dsl: DSLContext,
        private val dataSource: DataSource,
    ) {
        /** Registers the pool as bean [name] of [runner]'s context; a test takes no connection from it. */
        fun withDataSourceBean(
            runner: ApplicationContextRunner,
            name: String = "dataSource",
        ): ApplicationContextRunner =
            runner.withBean(name, DataSource::class.java, { dataSource }, { it.isPrimary = true })

        /** Hands the pool to [block], for a library class that is built over a DataSource. */
        fun <T> useDataSource(block: (DataSource) -> T): T = block(dataSource)

        /** Runs [block] in one transaction bound to [tenant] and [actor]. */
        fun <T> asTenant(
            tenant: TenantId,
            actor: String = TEST_ACTOR,
            block: DSLContext.() -> T,
        ): T = db.tenants.bind(tenant, actor) { runner.inTransaction { dsl.block() } }

        /** Runs [block] in one transaction bound to the platform tenant. */
        fun <T> asPlatform(
            actor: String = TEST_ACTOR,
            block: DSLContext.() -> T,
        ): T = db.tenants.bindPlatform(actor) { runner.inTransaction { dsl.block() } }

        /**
         * The rows of `schema.table` where every [equal] column equals its value, read as each of [of] and the platform
         * tenant in turn (each binding sees only its own rows, so the union is every row of those tenants).
         */
        fun rowsOf(
            table: String,
            of: Collection<TenantId>,
            vararg equal: Pair<String, Any?>,
        ): List<Map<String, Any?>> {
            val from = DSL.table(DSL.name(table.split('.')))
            val condition: Condition =
                equal.fold(DSL.noCondition()) { acc, (column, value) ->
                    acc.and(DSL.field(DSL.name(column)).eq(value))
                }
            val read: DSLContext.() -> List<Map<String, Any?>> = {
                select().from(from).where(condition).fetch().intoMaps().map { row ->
                    row.mapValues { (_, v) -> if (v is JSONB) v.data() else v }
                }
            }
            return (of.distinct().filter { !it.isPlatform }.flatMap { asTenant(it, block = read) }) +
                asPlatform(block = read)
        }
    }

    companion object {
        const val TEST_ACTOR = "test-operator"
    }
}
