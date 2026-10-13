package com.dynokore.platform.tenancyjooq

import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.Configuration
import org.jooq.DSLContext
import org.jooq.Name
import org.jooq.SQLDialect
import org.jooq.impl.DefaultConfiguration

/**
 * The framework tenant predicate on jOOQ (INV-ADR-3968 item 4a, INV-REQ-854 b6; owner ruling INV-Q-4113): the
 * VisitListener that injects the bound tenant's predicate, the ExecuteListener that checks it independently before
 * the statement runs, and a ConnectionProvider that hands out only tenancy-kernel's current transaction connection.
 */
object TenancyJooq {
    /**
     * Returns [configuration] derived with the tenancy listeners and connection provider for [tables].
     *
     * @param transactionRunner tenancy-kernel's runner; it must also be the [CurrentConnection] of the open
     *   transaction (tenancy-kernel's `TransactionRunnerImpl` is both).
     */
    fun configure(
        configuration: Configuration,
        tables: TenancyTables,
        tenants: TenantContext,
        transactionRunner: TransactionRunner,
    ): Configuration {
        val currentConnection =
            requireNotNull(transactionRunner as? CurrentConnection) {
                "the TransactionRunner must provide the current connection (tenancy-kernel's TransactionRunnerImpl)"
            }
        return configuration
            .derive(TenantConnectionProvider(transactionRunner, currentConnection))
            .deriveAppending(TenantPredicateInjector(tables, tenants))
            .deriveAppending(TenantStatementCheck(tables, tenants))
    }

    /**
     * The DSLContext of a platform-libs module (CS-18): PostgreSQL, on the transaction runner's connection, with the
     * tenancy listeners, for tables named with `DSL.name(schema, table)` and declared with their class.
     */
    fun dslContext(
        tables: Map<Name, TableClass>,
        tenants: TenantContext,
        transactionRunner: TransactionRunner,
    ): DSLContext = configure(
        DefaultConfiguration().set(SQLDialect.POSTGRES),
        TenancyTables.declared(tables),
        tenants,
        transactionRunner,
    ).dsl()
}
