package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantAwareDataSource
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import org.jooq.DSLContext
import org.jooq.Name
import org.jooq.impl.DSL
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

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

    /** `schema.table` names as jOOQ names, each declared [TableClass.PLATFORM_SCOPED] (no tenant predicate injected). */
    fun platformScoped(vararg tables: String): Map<Name, TableClass> =
        tables.associate { DSL.name(*it.split('.').toTypedArray()) to TableClass.PLATFORM_SCOPED }

    /** The SQLSTATE of the first [SQLException] in the cause chain of [failure], or null. */
    fun sqlState(failure: Throwable?): String? =
        generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    /**
     * A login's own TransactionRunner and DSLContext over [dataSource], with [tables] declared, for the statements a
     * test runs as that login (owner rulings INV-Q-4508 (A), INV-Q-5327 (D)).
     */
    class Access(
        dataSource: DataSource,
        tables: Map<Name, TableClass>,
    ) {
        val tenants = TenantContextImpl()
        val runner = TransactionRunnerImpl(TenantAwareDataSource(dataSource), tenants)
        val dsl: DSLContext = TenancyJooq.dslContext(tables, tenants, runner)

        /** [block] in one transaction bound to [tenant]. */
        fun <T> asTenant(
            tenant: UUID,
            block: (DSLContext) -> T,
        ): T = tenants.bind(TenantId(tenant), ACTOR) { runner.inTransaction { block(dsl) } }

        /** [block] in one transaction under the platform binding. */
        fun <T> asPlatform(block: (DSLContext) -> T): T =
            tenants.bindPlatform(ACTOR) { runner.inTransaction { block(dsl) } }

        private companion object {
            const val ACTOR = "svc:test/job:probe"
        }
    }
}
