package com.dynokore.platform.tenancyjooq

import org.jooq.Condition
import org.jooq.Field
import org.jooq.FieldOrRow
import org.jooq.Row
import org.jooq.Select
import org.jooq.SelectQuery
import org.jooq.Table
import org.jooq.TableLike
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
    private val verified = IdentityHashMap<Table<*>, Int>()

    override fun visitStart(context: VisitContext) {
        val part = context.queryPart()
        StatementGuard.refuseUnsupported(part)
        if (context.context().declareTables() && isBaseTable(part)) {
            val ref = tables.classify(part as Table<*>)
            if (ref is DataRef && ref.tableClass != TableClass.PLATFORM_SCOPED) declared.merge(part, 1, Int::plus)
        }
        when (part) {
            is SelectQuery<*> -> select(part)
            is QOM.Update<*> -> update(part)
            is QOM.Delete<*> -> delete(part)
            is QOM.Insert<*> -> insert(part)
            is QOM.Merge<*> -> merge(part)
        }
    }

    /** After the render: every tenant table declared in the statement was matched by its predicate. */
    fun requireAllVerified() {
        val unverified = declared.filter { (table, count) -> (verified[table] ?: 0) < count }.keys
        if (unverified.isNotEmpty()) {
            throw TenantPredicateViolation(
                "tenant tables without the tenant predicate: ${unverified.map { it.qualifiedName }}",
            )
        }
    }

    private fun select(select: Select<*>) = requireInPlace(pendingOf(select.`$from`()), conjuncts(select.`$where`()))

    private fun update(update: QOM.Update<*>) {
        requireNoTenantColumnSet(update.`$set`().keys)
        target(update.`$table`())?.let { requireCondition(it, predicates.write(it), conjuncts(update.`$where`())) }
        requireInPlace(pendingOf(update.`$from`()), conjuncts(update.`$where`()))
    }

    private fun delete(delete: QOM.Delete<*>) {
        target(delete.`$from`())?.let { requireCondition(it, predicates.write(it), conjuncts(delete.`$where`())) }
        requireInPlace(pendingOf(delete.`$using`()), conjuncts(delete.`$where`()))
    }

    private fun insert(insert: QOM.Insert<*>) {
        val target = target(insert.`$into`()) ?: return
        val index = insert.`$columns`().indexOfFirst { isTenantColumn(it) }
        if (index < 0) throw TenantPredicateViolation("${target.base.qualifiedName}: an INSERT without the tenant")
        val select = insert.`$select`()
        val values =
            if (select != null) {
                // INSERT ... SELECT: the injector appends the tenant as the last column and the last select item.
                if (index != insert.`$columns`().lastIndex) listOf(null) else listOf(select.`$select`().lastOrNull())
            } else {
                insert.`$values`().map { it.`$fields`().getOrNull(index) }
            }
        if (values.isEmpty() || values.any { it !is Field<*> || !isTenantValue(it, predicates.tenant) }) {
            throw TenantPredicateViolation("${target.base.qualifiedName}: an INSERT not set to the bound tenant")
        }
        if (insert.`$updateSet`().isNotEmpty()) {
            requireNoTenantColumnSet(insert.`$updateSet`().keys)
            requireCondition(target, predicates.write(target), conjuncts(insert.`$updateWhere`()), count = false)
        }
        verified.merge(target.base, 1, Int::plus)
    }

    private fun merge(merge: QOM.Merge<*>) {
        val target = target(merge.`$into`())
        val source = sourceOf(merge.`$using`() ?: throw TenantPredicateViolation("a MERGE without a source"))
        merge.`$whenMatched`().forEach { clause ->
            requireNoTenantColumnSet(clause.`$updateSet`().keys)
            source.forEach {
                requireCondition(it.ref, it.condition(predicates), conjuncts(clause.`$where`()), count = false)
            }
        }
        merge.`$whenNotMatched`().forEach { clause ->
            source.forEach {
                requireCondition(it.ref, it.condition(predicates), conjuncts(clause.`$where`()), count = false)
            }
            if (target != null) {
                val index = clause.`$columns`().indexOfFirst { isTenantColumn(it) }
                val values = clause.`$values`().map { it.`$fields`().getOrNull(index) }
                val tenantSet =
                    index >= 0 &&
                        values.isNotEmpty() &&
                        values.all { it != null && isTenantValue(it, predicates.tenant) }
                if (!tenantSet) {
                    throw TenantPredicateViolation(
                        "${target.base.qualifiedName}: a MERGE insert not set to the bound tenant",
                    )
                }
            }
        }
        if (target != null) {
            if (merge.`$whenNotMatchedBySource`().isNotEmpty()) {
                throw TenantPredicateViolation("MERGE WHEN NOT MATCHED BY SOURCE on ${target.base.qualifiedName}")
            }
            requireCondition(target, predicates.write(target), conjuncts(merge.`$on`()))
        }
        source.forEach { verified.merge(it.ref.base, 1, Int::plus) }
    }

    private fun sourceOf(using: TableLike<*>): List<Pending> {
        if (using !is Table<*>) return emptyList()
        if (using is QOM.JoinTable<*, *>) throw TenantPredicateViolation("a join as a MERGE source")
        return pendingOf(listOf(using))
    }

    /** The data table a write targets, re-checking the write rules; null for a PLATFORM_SCOPED table. */
    private fun target(table: Table<*>): DataRef? {
        val ref = tables.classify(table) as? DataRef ?: throw TenantPredicateViolation("a write of $table")
        return when (ref.tableClass) {
            TableClass.PLATFORM_SCOPED ->
                if (predicates.tenant.isPlatform) {
                    null
                } else {
                    throw TenantPredicateViolation("a PLATFORM_SCOPED write under a tenant")
                }

            TableClass.REFERENCE ->
                if (predicates.tenant.isPlatform) {
                    throw TenantPredicateViolation("a REFERENCE write under the platform binding")
                } else {
                    ref
                }

            TableClass.TENANT -> ref
        }
    }

    /** The predicates the tables of a FROM list owe to its WHERE; outer joins are checked on the way. */
    private fun pendingOf(list: List<Table<*>>): List<Pending> = list.flatMap { pendingOf(it) }

    private fun pendingOf(table: Table<*>): List<Pending> {
        if (table !is QOM.JoinTable<*, *>) {
            val ref = tables.classify(table)
            return if (ref is DataRef && ref.tableClass != TableClass.PLATFORM_SCOPED) {
                listOf(Pending(ref, false))
            } else {
                emptyList()
            }
        }
        val left = pendingOf(table.`$table1`())
        val right = pendingOf(table.`$table2`())
        val on = (table as? QOM.QualifiedJoin<*, *>)?.`$on`().let { conjuncts(it) }
        return when (joinKind(table)) {
            JoinKind.INNER -> left + right
            JoinKind.LEFT -> requireInPlace(right, on).let { left }
            JoinKind.RIGHT -> requireInPlace(left, on).let { right }
            JoinKind.FULL -> requireInPlace(left + right, on).let { (left + right).map { Pending(it.ref, true) } }
            JoinKind.LEFT_NO_ON -> requireNone(right).let { left }
            JoinKind.RIGHT_NO_ON -> requireNone(left).let { right }
            JoinKind.FULL_NO_ON -> requireNone(left + right).let { emptyList() }
        }
    }

    private fun requireNone(pending: List<Pending>) {
        if (pending.isNotEmpty()) {
            throw TenantPredicateViolation(
                "an outer join without ON reads ${pending.map { it.ref.base.qualifiedName }}",
            )
        }
    }

    private fun requireInPlace(
        pending: List<Pending>,
        place: List<Condition>,
    ) = pending.forEach { requireCondition(it.ref, it.condition(predicates), place) }

    private fun requireCondition(
        ref: DataRef,
        expected: Condition,
        place: List<Condition>,
        count: Boolean = true,
    ) {
        if (place.none { it == expected }) {
            throw TenantPredicateViolation("${ref.base.qualifiedName} without its tenant predicate $expected")
        }
        if (count) verified.merge(ref.base, 1, Int::plus)
    }

    private fun requireNoTenantColumnSet(targets: Collection<FieldOrRow>) {
        val fields = targets.flatMap { if (it is Row) it.`$fields`() else listOf(it as Field<*>) }
        if (fields.any { isTenantColumn(it) }) throw TenantPredicateViolation("a write that sets the tenant column")
    }
}
