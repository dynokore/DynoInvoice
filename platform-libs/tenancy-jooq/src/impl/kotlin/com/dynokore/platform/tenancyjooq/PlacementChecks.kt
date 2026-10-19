package com.dynokore.platform.tenancyjooq

import org.jooq.Condition
import org.jooq.Table
import org.jooq.impl.QOM
import java.util.IdentityHashMap

/**
 * The placement half of [TenantPredicateCheck]: which predicate a table owes, where it must sit, and the count of
 * tables that were matched.
 */
internal class PlacementChecks(
    private val tables: TenancyTables,
    private val predicates: Predicates,
) {
    private val verified = IdentityHashMap<Table<*>, Int>()

    fun verifiedCount(table: Table<*>): Int = verified[table] ?: 0

    fun verify(table: Table<*>) {
        verified.merge(table, 1, Int::plus)
    }

    /** The predicates the tables of a FROM list owe to its WHERE; outer joins are checked on the way. */
    fun pendingOf(list: List<Table<*>>): List<Pending> = list.flatMap { pendingOf(it) }

    fun pendingOf(table: Table<*>): List<Pending> {
        if (table !is QOM.JoinTable<*, *>) return leafPending(table)
        val left = pendingOf(table.`$table1`())
        val right = pendingOf(table.`$table2`())
        val on = conjuncts((table as? QOM.QualifiedJoin<*, *>)?.`$on`())
        return joinPending(joinKind(table), left, right, on)
    }

    private fun joinPending(
        kind: JoinKind,
        left: List<Pending>,
        right: List<Pending>,
        on: List<Condition>,
    ): List<Pending> =
        when (kind) {
            JoinKind.INNER -> left + right
            JoinKind.LEFT, JoinKind.RIGHT, JoinKind.FULL -> outerPending(kind, left, right, on)
            JoinKind.LEFT_NO_ON, JoinKind.RIGHT_NO_ON, JoinKind.FULL_NO_ON -> outerNoOnPending(kind, left, right)
        }

    private fun outerPending(
        kind: JoinKind,
        left: List<Pending>,
        right: List<Pending>,
        on: List<Condition>,
    ): List<Pending> =
        when (kind) {
            JoinKind.LEFT -> requireInPlace(right, on).let { left }
            JoinKind.RIGHT -> requireInPlace(left, on).let { right }
            else -> requireInPlace(left + right, on).let { (left + right).map { p -> Pending(p.ref, true) } }
        }

    private fun outerNoOnPending(
        kind: JoinKind,
        left: List<Pending>,
        right: List<Pending>,
    ): List<Pending> =
        when (kind) {
            JoinKind.LEFT_NO_ON -> requireNone(right).let { left }
            JoinKind.RIGHT_NO_ON -> requireNone(left).let { right }
            else -> requireNone(left + right).let { emptyList() }
        }

    private fun leafPending(table: Table<*>): List<Pending> {
        val ref = tables.classify(table)
        return if (ref is DataRef && ref.tableClass != TableClass.PLATFORM_SCOPED) {
            listOf(Pending(ref, false))
        } else {
            emptyList()
        }
    }

    private fun requireNone(pending: List<Pending>) {
        if (pending.isNotEmpty()) {
            refuse("an outer join without ON reads ${pending.map { it.ref.base.qualifiedName }}")
        }
    }

    fun requireInPlace(
        pending: List<Pending>,
        place: List<Condition>,
    ) = pending.forEach { requireCondition(it.ref, it.condition(predicates), place) }

    fun requireCondition(
        ref: DataRef,
        expected: Condition,
        place: List<Condition>,
        count: Boolean = true,
    ) {
        if (place.none { it == expected }) {
            refuse("${ref.base.qualifiedName} without its tenant predicate $expected")
        }
        if (count) verify(ref.base)
    }
}
