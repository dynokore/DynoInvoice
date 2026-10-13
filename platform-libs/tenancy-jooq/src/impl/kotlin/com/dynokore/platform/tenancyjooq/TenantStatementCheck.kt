package com.dynokore.platform.tenancyjooq

import com.dynokore.platform.tenancykernel.TenantContext
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
    private val tables: TenancyTables,
    private val tenants: TenantContext,
) : ExecuteListener {
    override fun renderEnd(context: ExecuteContext) {
        val query =
            context.query()
                ?: throw TenantPredicateViolation(
                    "only single statements run on a tenancy-jooq DSLContext (batch or routine)",
                )
        val check = TenantPredicateCheck(tables, Predicates(BoundTenant.require(tenants)))
        val checked = context.configuration().deriveAppending(check).dsl().render(query)
        check.requireAllVerified()
        if (checked != context.sql()) {
            throw TenantPredicateViolation("the checked statement differs from the statement about to run")
        }
    }
}
