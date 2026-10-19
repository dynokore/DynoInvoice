package com.dynokore.platform.tenancyjooq

import org.jooq.Select
import org.jooq.SelectQuery
import org.jooq.Table
import org.jooq.VisitContext
import org.jooq.VisitListener
import org.jooq.impl.QOM
import java.util.IdentityHashMap

/**
 * The independent second check (INV-ADR-3968 item 4a): one instance follows one re-render of a statement, after the
 * injector, and reads the query model as it is rendered. Every TENANT or REFERENCE table the statement declares must
 * be matched by its predicate in the place it belongs (WHERE, or the ON clause of an outer join's nullable side) and
 * every write must carry the bound tenant; anything else is a [TenantPredicateViolation].
 */
internal class TenantPredicateCheck(
    private val tables: TenancyTables,
    private val predicates: Predicates,
) : VisitListener {
    private val declared = IdentityHashMap<Table<*>, Int>()
    private val placement = PlacementChecks(tables, predicates)
    private val writes = WriteChecks(tables, predicates, placement)

    override fun visitEnd(context: VisitContext) {
        StatementGuard.visitEnd(context)
    }

    override fun visitStart(context: VisitContext) {
        StatementGuard.visitStart(context)
        val part = context.queryPart()
        if (context.context().declareTables() && isBaseTable(part)) {
            val ref = tables.classify(part as Table<*>)
            if (ref is DataRef && ref.tableClass != TableClass.PLATFORM_SCOPED) declared.merge(part, 1, Int::plus)
        }
        when (part) {
            is SelectQuery<*> -> select(part)
            is QOM.Update<*> -> update(part)
            is QOM.Delete<*> -> delete(part)
            is QOM.Insert<*> -> writes.insert(part)
            is QOM.Merge<*> -> writes.merge(part)
        }
    }

    /** After the render: every tenant table declared in the statement was matched by its predicate. */
    fun requireAllVerified() {
        val unverified = declared.filter { (table, count) -> placement.verifiedCount(table) < count }.keys
        if (unverified.isNotEmpty()) {
            refuse("tenant tables without the tenant predicate: ${unverified.map { it.qualifiedName }}")
        }
    }

    private fun select(select: Select<*>) =
        placement.requireInPlace(placement.pendingOf(select.`$from`()), conjuncts(select.`$where`()))

    private fun update(update: QOM.Update<*>) {
        writes.requireNoTenantColumnSet(update.`$set`().keys)
        writes.target(update.`$table`())?.let {
            placement.requireCondition(it, predicates.write(it), conjuncts(update.`$where`()))
        }
        placement.requireInPlace(placement.pendingOf(update.`$from`()), conjuncts(update.`$where`()))
    }

    private fun delete(delete: QOM.Delete<*>) {
        writes.target(delete.`$from`())?.let {
            placement.requireCondition(it, predicates.write(it), conjuncts(delete.`$where`()))
        }
        placement.requireInPlace(placement.pendingOf(delete.`$using`()), conjuncts(delete.`$where`()))
    }
}
