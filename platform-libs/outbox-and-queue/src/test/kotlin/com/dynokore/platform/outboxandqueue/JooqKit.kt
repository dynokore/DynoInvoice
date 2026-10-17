package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.LaneGate
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TestPoolFixture
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.Name
import org.jooq.impl.DSL
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
        val runner = pool.runner(tenants, lanes)
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

            /** A fixture consumer's own login [role] (password `<role>_pw`). */
            fun role(
                fixture: OutboxPostgresFixture,
                role: String,
                tables: Map<Name, TableClass>,
            ) = Access(fixture.jdbcUrl(), role, "${role}_pw", tables, null)

            fun purge(
                fixture: OutboxPostgresFixture,
                tables: Map<Name, TableClass>,
            ) = Access(fixture.jdbcUrl(), OutboxPostgresFixture.PURGE, "eventlog_purge_pw", tables, null)
        }
    }
}
