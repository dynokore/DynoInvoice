package com.dynokore.platform.tenancyjooq

import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.Configuration
import org.jooq.ExecuteContext
import org.jooq.ExecuteListener

/**
 * The ExecuteListener (INV-ADR-3968 item 4a): after jOOQ renders a statement and before it is prepared, the statement
 * is rendered again with a [TenantPredicateCheck] following the configured listeners, and refused unless every
 * TENANT or REFERENCE table carries its predicate and the checked SQL is exactly the SQL about to run. It runs whether
 * or not the injector is installed, so a configuration without the injector fails here, before any SQL reaches the
 * server. A batch, a routine call or anything else without a single query is refused.
 */
internal class TenantStatementCheck(
    internal val tables: TenancyTables,
    private val tenants: TenantContext,
) : ExecuteListener {
    override fun renderEnd(context: ExecuteContext) {
        val query =
            context.query()
                ?: refuse("only single statements run on a tenancy-jooq DSLContext (batch or routine)")
        requireOwnedConfiguration(context.configuration())
        val rendered = context.sql() ?: refuse("the statement rendered no SQL")
        RenderedSql.require(rendered)
        val check = TenantPredicateCheck(tables, Predicates(BoundTenant.require(tenants)))
        val checked =
            context
                .configuration()
                .deriveAppending(check)
                .dsl()
                .render(query)
        check.requireAllVerified()
        if (checked != rendered) {
            refuse("the checked statement differs from the statement about to run")
        }
        context.data(CHECKED, checked)
    }

    /** No listener may change the checked statement between rendering and preparing it. */
    override fun prepareStart(context: ExecuteContext) {
        if (context.data(CHECKED) != context.sql()) {
            refuse("the statement about to be prepared is not the checked statement")
        }
    }

    private companion object {
        val CHECKED: Any = Any()

        /**
         * The settings, the dialect and the listeners tenancy-jooq owns, on the configuration the statement runs on:
         * no VisitListener but tenancy-jooq's injector can rewrite the statement, and no ExecuteListener after this
         * check can change it before it runs.
         */
        fun requireOwnedConfiguration(configuration: Configuration) {
            OwnedSettings.require(configuration)
            if (configuration.visitListenerProviders().any { it.provide() !is TenantPredicateInjector }) {
                refuse("a VisitListener other than tenancy-jooq's runs on this configuration")
            }
            if (configuration.executeListenerProviders().lastOrNull()?.provide() !is TenantStatementCheck) {
                refuse("an ExecuteListener runs after tenancy-jooq's statement check")
            }
        }
    }
}
