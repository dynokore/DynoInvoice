package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.LaneGate
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TestPoolFixture
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.DataType
import org.jooq.Field
import org.jooq.Name
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.sql.SQLException
import java.util.UUID

/** The DSLContexts of the tests, built as the service-runtime wiring builds them (INV-Q-5275). */
object JooqKit {
    /** The module's DSLContext of [schema] over [runner] (which is also the current connection). */
    fun module(
        schema: String,
        tenants: TenantContext,
        runner: TransactionRunner,
    ): DSLContext = TenancyJooq.dslContext(OutboxSchema.jooqTables(schema), tenants, runner)

    /** The event-log DSLContext over [scope]'s own runner, with the topic tables of [catalogue]. */
    fun eventLog(
        scope: TenantScope,
        catalogue: EventCatalogue,
    ): DSLContext =
        TenancyJooq.dslContext(
            EventLogSchema.jooqTables(
                (
                    catalogue.entries().map { it.topic } + catalogue.replayTopics().map { it.topic } +
                        catalogue.requestTopics().map { it.topic }
                ).toSet(),
            ),
            scope.tenants,
            scope.transactions,
        )

    /** `schema.table` names as jOOQ names, each declared PLATFORM_SCOPED (no tenant predicate injected). */
    fun platformScoped(vararg tables: String): Map<Name, TableClass> =
        tables.associate { DSL.name(*it.split('.').toTypedArray()) to TableClass.PLATFORM_SCOPED }

    /**
     * Records a dead letter through the sys-owned [OutboxSchema.DEAD_LETTER_RECORD] (the runtime login holds EXECUTE),
     * a jOOQ scalar call under the platform binding the production code uses; a null [tenant] is stored under the
     * platform tenant. Returns the new letter's id.
     */
    fun recordDeadLetter(
        fixture: OutboxPostgresFixture,
        schema: String,
        consumer: String,
        topic: String,
        offset: Long,
        tenant: UUID?,
        reason: String,
        raw: String,
    ): Long =
        Access.runtime(fixture, OutboxSchema.jooqTables(schema)).single().asPlatform { dsl ->
            dsl
                .select(
                    DSL.function(
                        DSL.name(schema, OutboxSchema.DEAD_LETTER_RECORD),
                        SQLDataType.BIGINT,
                        DSL.`val`(consumer, SQLDataType.CLOB),
                        DSL.`val`(topic, SQLDataType.CLOB),
                        DSL.`val`(offset, SQLDataType.BIGINT),
                        DSL.`val`(tenant, SQLDataType.UUID),
                        DSL.`val`(reason, SQLDataType.CLOB),
                        DSL.`val`(raw, SQLDataType.CLOB),
                    ),
                ).fetchSingle()
                .value1()
        }

    /** One column of an inserted row: its [name], jOOQ [type] and [value], bound as a typed bind value. */
    open class Col<T>(
        val name: String,
        val type: DataType<T>,
        val value: T?,
    ) {
        fun field(): Field<T> = DSL.field(DSL.name(name), type)

        open fun bind(): Field<T> = DSL.`val`(value, type)
    }

    /** A column set from a jOOQ expression [expr] (a function call), not from a bind value. */
    class ExprCol<T>(
        name: String,
        type: DataType<T>,
        private val expr: Field<T>,
    ) : Col<T>(name, type, null) {
        override fun bind(): Field<T> = expr
    }

    /** `insert into schema.table (columns) values (binds)` through [dsl]; every value a typed bind (no plain SQL). */
    fun insert(
        dsl: DSLContext,
        schema: String,
        table: String,
        vararg columns: Col<*>,
    ): Int =
        dsl
            .insertInto(DSL.table(DSL.name(schema, table)), columns.map { it.field() })
            .values(columns.map { it.bind() })
            .execute()

    /**
     * The SQLSTATE the database gives [sql] run by the login [user] with [tenant] bound (none when null), or null when
     * the statement is not refused: [sql] must return a row when it succeeds (end an update or delete with
     * `returning 1`), so a statement that is not refused cannot pass as refused. Run through TenancyProbe (route R5).
     */
    fun refusal(
        fixture: OutboxPostgresFixture,
        user: String,
        password: String,
        sql: String,
        tenant: UUID? = null,
    ): String? {
        val probe = TenancyProbe.connect(fixture.jdbcUrl(), user, password)
        val failure =
            runCatching {
                if (tenant ==
                    null
                ) {
                    probe.unbound { fetchValue(sql) }
                } else {
                    probe.inTenant(TenantId(tenant)) { fetchValue(sql) }
                }
            }.exceptionOrNull()
        return sqlState(failure)
    }

    /** The SQLSTATE of the first [SQLException] in the cause chain of [failure], or null. */
    fun sqlState(failure: Throwable?): String? =
        generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    /**
     * A login's own pool (tenancy-kernel's TestPoolFixture), TransactionRunner and DSLContext, with [tables] declared,
     * for the statements a test runs as that login (owner rulings INV-Q-4508 (A), INV-Q-5327 (D)). A pool made for one
     * call ([single]) is closed after it.
     */
    class Access private constructor(
        url: String,
        user: String,
        password: String,
        tables: Map<Name, TableClass>,
        lanes: LaneGate?,
    ) : AutoCloseable {
        val tenants = TenantContextImpl()
        private val pool = TestPoolFixture(url, user, password, poolName = user)
        val runner = pool.runner(tenants, lanes, keys = SigningFixture.keys())
        val dsl: DSLContext = TenancyJooq.dslContext(tables, tenants, runner)
        private var closeAfterUse = false

        /** Closes the pool after the next [asTenant] or [asPlatform] call. */
        fun single(): Access = also { closeAfterUse = true }

        /** [block] in one transaction bound to [tenant]. */
        fun <T> asTenant(
            tenant: UUID,
            block: (DSLContext) -> T,
        ): T = finish { tenants.bind(TenantId(tenant), ACTOR) { runner.inTransaction { block(dsl) } } }

        /** [block] in one transaction under the platform binding. */
        fun <T> asPlatform(block: (DSLContext) -> T): T =
            finish { tenants.bindPlatform(ACTOR) { runner.inTransaction { block(dsl) } } }

        override fun close() = pool.close()

        private fun <T> finish(block: () -> T): T =
            try {
                block()
            } finally {
                if (closeAfterUse) close()
            }

        companion object {
            private const val ACTOR = "svc:test/job:probe"

            fun admin(
                fixture: OutboxPostgresFixture,
                tables: Map<Name, TableClass>,
                lanes: LaneGate? = null,
            ) = Access(fixture.jdbcUrl(), fixture.adminUser(), fixture.adminPassword(), tables, lanes)

            /** The runtime login [OutboxPostgresFixture.APP], which is also the event-log login. */
            fun runtime(
                fixture: OutboxPostgresFixture,
                tables: Map<Name, TableClass>,
                lanes: LaneGate? = null,
            ) = Access(fixture.jdbcUrl(), OutboxPostgresFixture.APP, OutboxPostgresFixture.APP_PASSWORD, tables, lanes)

            /** The owner login [OutboxPostgresFixture.OWNER]: it changes the state the runtime login cannot. */
            fun owner(
                fixture: OutboxPostgresFixture,
                tables: Map<Name, TableClass>,
                lanes: LaneGate? = null,
            ) = Access(
                fixture.jdbcUrl(),
                OutboxPostgresFixture.OWNER,
                OutboxPostgresFixture.OWNER_PASSWORD,
                tables,
                lanes,
            )

            /** A fixture consumer's own login [role] (password `<role>_pw`). */
            fun role(
                fixture: OutboxPostgresFixture,
                role: String,
                tables: Map<Name, TableClass>,
            ) = Access(fixture.jdbcUrl(), role, "${role}_pw", tables, null)

            /** The event log's migration login [OutboxPostgresFixture.MIGRATE], owner of schema `eventlog`. */
            fun migrate(
                fixture: OutboxPostgresFixture,
                tables: Map<Name, TableClass>,
            ) = Access(
                fixture.jdbcUrl(),
                OutboxPostgresFixture.MIGRATE,
                OutboxPostgresFixture.MIGRATE_PASSWORD,
                tables,
                null,
            )

            fun purge(
                fixture: OutboxPostgresFixture,
                tables: Map<Name, TableClass>,
            ) = Access(fixture.jdbcUrl(), OutboxPostgresFixture.PURGE, "eventlog_purge_pw", tables, null)
        }
    }
}
