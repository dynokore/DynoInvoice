package com.dynokore.platform.tenancyjooq

import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.QueryPart
import org.jooq.SelectQuery
import org.jooq.VisitContext
import org.jooq.VisitListener
import org.jooq.impl.QOM

/**
 * The framework tenant predicate (INV-ADR-3968 item 4a, INV-REQ-854 b6): while a statement renders, every SELECT
 * (each UNION/INTERSECT/EXCEPT branch, subquery, derived table and CTE body is its own SELECT), UPDATE, DELETE,
 * INSERT and MERGE is replaced by a copy that carries the bound tenant's predicate, and the tenant column of every
 * write is set to the bound tenant (see [Rewriter]). Plain SQL, TRUNCATE, DDL and tables outside the declared schema
 * are refused ([StatementGuard], [classify]).
 */
internal class TenantPredicateInjector(
    private val tables: TenancyTables,
    private val tenants: TenantContext,
) : VisitListener {
    override fun visitStart(context: VisitContext) {
        val part = context.queryPart()
        StatementGuard.refuseUnsupported(part)
        @Suppress("UNCHECKED_CAST")
        val replaced = context.data().getOrPut(REPLACED) { identitySet<QueryPart>() } as MutableSet<QueryPart>
        if (part in replaced) return
        if (context.context().declareTables() && isBaseTable(part)) {
            // a table outside the declared schema is refused wherever it is declared
            tables.classify(part as org.jooq.Table<*>)
        }
        val rewritten =
            when (part) {
                is SelectQuery<*> -> rewriter().select(part)
                is QOM.Update<*> -> rewriter().update(part)
                is QOM.Delete<*> -> rewriter().delete(part)
                is QOM.Insert<*> -> rewriter().insert(part)
                is QOM.Merge<*> -> rewriter().merge(part)
                else -> part
            }
        if (rewritten !== part) {
            replaced += rewritten
            context.queryPart(rewritten)
        }
    }

    private fun rewriter(): Rewriter = Rewriter(tables, Predicates(BoundTenant.require(tenants)))

    private companion object {
        val REPLACED: Any = Any()
    }
}
