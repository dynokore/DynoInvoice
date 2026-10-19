package com.dynokore.platform.tenancyjooq

import org.jooq.Condition
import org.jooq.QueryPart
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.QOM

/** [existing] and the [added] conditions in one conjunction; with nothing added, [existing] or no condition. */
internal fun andConditions(
    existing: Condition?,
    added: List<Condition>,
): Condition = if (added.isEmpty()) existing ?: DSL.noCondition() else DSL.and(listOfNotNull(existing) + added)

/** The rewrite of a FROM (or UPDATE FROM, DELETE USING) list: where each table's predicate goes. */
internal class JoinRewriter(
    private val tables: TenancyTables,
    private val predicates: Predicates,
) {
    /** Rewrites a list of tables; the pending predicates belong in its WHERE. */
    fun rewriteTables(list: List<Table<*>>): Pair<List<Table<*>>, List<Pending>> {
        val pending = mutableListOf<Pending>()
        val rewritten =
            list.map { table ->
                val (result, own) = rewriteTable(table)
                pending += own
                result
            }
        return rewritten to pending
    }

    fun rewriteTable(table: Table<*>): Pair<Table<*>, List<Pending>> {
        if (table !is QOM.JoinTable<*, *>) return table to leafPending(table)
        val (left, leftPending) = rewriteTable(table.`$table1`())
        val (right, rightPending) = rewriteTable(table.`$table2`())
        val join = table.`$table1`(left).`$table2`(right)
        return when (joinKind(table)) {
            JoinKind.INNER -> {
                join to leftPending + rightPending
            }

            JoinKind.LEFT -> {
                withOn(join, rightPending) to leftPending
            }

            JoinKind.RIGHT -> {
                withOn(join, leftPending) to rightPending
            }

            JoinKind.FULL -> {
                withOn(join, leftPending + rightPending) to (leftPending + rightPending).map { Pending(it.ref, true) }
            }

            JoinKind.LEFT_NO_ON -> {
                refuseNullableData(table, rightPending).let { join to leftPending }
            }

            JoinKind.RIGHT_NO_ON -> {
                refuseNullableData(table, leftPending).let { join to rightPending }
            }

            JoinKind.FULL_NO_ON -> {
                refuseNullableData(table, leftPending + rightPending).let { join to emptyList() }
            }
        }
    }

    private fun leafPending(table: Table<*>): List<Pending> =
        when (val ref = tables.classify(table)) {
            is DataRef -> if (ref.tableClass == TableClass.PLATFORM_SCOPED) emptyList() else listOf(Pending(ref, false))
            NoData -> emptyList()
        }

    private fun withOn(
        join: QOM.JoinTable<*, *>,
        pending: List<Pending>,
    ): Table<*> {
        if (pending.isEmpty()) return join
        val qualified = join as QOM.QualifiedJoin<*, *>
        return qualified.`$on`(andConditions(qualified.`$on`(), pending.map { it.condition(predicates) }))
    }

    private fun refuseNullableData(
        join: QueryPart,
        pending: List<Pending>,
    ) {
        if (pending.isNotEmpty()) {
            refuse(
                "an outer join without ON (NATURAL, USING or OUTER APPLY) whose nullable side reads " +
                    "${pending.map { it.ref.base.qualifiedName }}; write the join with ON: $join",
            )
        }
    }
}
