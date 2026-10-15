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
     * The configuration must keep jOOQ's default settings except execution limits and logging, run on the
     * POSTGRES dialect and have no VisitListener: anything else throws [TenantPredicateViolation].
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
        // The settings and dialect tenancy-jooq owns, and no VisitListener of the consumer's (review of !61); the
        // same checks run again on every statement.
        OwnedSettings.require(configuration)
        if (configuration.visitListenerProviders().isNotEmpty()) {
            throw TenantPredicateViolation("tenancy-jooq owns the VisitListeners: the configuration already has one")
        }
        return configuration
            .derive(TenantConnectionProvider(transactionRunner, currentConnection))
            .deriveAppending(TenantPredicateInjector(tables, tenants))
            .deriveAppending(TenantStatementCheck(tables, tenants))
    }

    /**
     * [configure] applied in place (INV-Q-5105): validates [configuration] as [configure] does, then sets tenancy-jooq's
     * connection provider, VisitListener and ExecuteListener on that object, for a caller that is handed a mutable
     * `DefaultConfiguration` (Spring Boot's `DefaultConfigurationCustomizer`) and may not call a listener setter itself.
     */
    fun install(
        configuration: DefaultConfiguration,
        tables: TenancyTables,
        tenants: TenantContext,
        transactionRunner: TransactionRunner,
    ) {
        val owned = configure(configuration, tables, tenants, transactionRunner)
        configuration.set(owned.connectionProvider())
        configuration.set(*owned.visitListenerProviders())
        configuration.set(*owned.executeListenerProviders())
    }

    /**
     * Why [configuration] is not tenancy-jooq's (INV-Q-5105), or null when it is: its connection provider is
     * tenancy-jooq's, its VisitListeners are exactly tenancy-jooq's predicate injector, and its last ExecuteListener is
     * tenancy-jooq's statement check. The listener classes stay internal.
     */
    fun problem(configuration: Configuration): String? =
        when {
            configuration.connectionProvider() !is TenantConnectionProvider -> {
                "its connection provider is not tenancy-jooq's"
            }

            configuration.visitListenerProviders().let { providers ->
                providers.isEmpty() || providers.any { it.provide() !is TenantPredicateInjector }
            } -> {
                "its VisitListeners are not exactly tenancy-jooq's predicate injector"
            }

            configuration.executeListenerProviders().lastOrNull()?.provide() !is TenantStatementCheck -> {
                "its last ExecuteListener is not tenancy-jooq's statement check"
            }

            else -> {
                null
            }
        }

    /**
     * The DSLContext of a platform-libs module (CS-18): PostgreSQL, on the transaction runner's connection, with the
     * tenancy listeners, for tables named with `DSL.name(schema, table)` and declared with their class.
     */
    fun dslContext(
        tables: Map<Name, TableClass>,
        tenants: TenantContext,
        transactionRunner: TransactionRunner,
    ): DSLContext =
        configure(
            DefaultConfiguration().set(SQLDialect.POSTGRES),
            TenancyTables.declared(tables),
            tenants,
            transactionRunner,
        ).dsl()
}
