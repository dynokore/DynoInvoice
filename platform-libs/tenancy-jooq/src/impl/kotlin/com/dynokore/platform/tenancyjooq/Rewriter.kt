package com.dynokore.platform.tenancyjooq

import org.jooq.Condition
import org.jooq.Field
import org.jooq.FieldOrRow
import org.jooq.QueryPart
import org.jooq.Row
import org.jooq.Select
import org.jooq.Table
import org.jooq.TableLike
import org.jooq.impl.DSL
import org.jooq.impl.QOM

/**
 * The injector's rewrite of one statement (INV-ADR-3968 item 4a): it returns a copy of the statement with the tenant
 * predicates added and the tenant column set; the statement the caller built is never changed.
 */
internal class Rewriter(
    private val tables: TenancyTables,
    private val predicates: Predicates,
) {
    private val tenant get() = predicates.tenant

    fun select(select: Select<*>): Select<*> {
        val (from, pending) = rewriteTables(select.`$from`())
        val unchanged = from.indices.all { from[it] === select.`$from`()[it] }
        if (pending.isEmpty() && unchanged) return select
        return select.`$from`(from).`$where`(and(select.`$where`(), pending.map { it.condition(predicates) }))
    }

    fun update(update: QOM.Update<*>): QOM.Update<*> {
        val target = writeTarget(update.`$table`(), "UPDATE")
        refuseTenantColumnSet(update.`$set`().keys, "an UPDATE")
        val (from, pending) = rewriteTables(update.`$from`())
        val conditions = listOfNotNull(target?.let { predicates.write(it) }) + pending.map { it.condition(predicates) }
        return update.`$from`(from).`$where`(and(update.`$where`(), conditions))
    }

    fun delete(delete: QOM.Delete<*>): QOM.Delete<*> {
        val target = writeTarget(delete.`$from`(), "DELETE")
        val (using, pending) = rewriteTables(delete.`$using`())
        val conditions = listOfNotNull(target?.let { predicates.write(it) }) + pending.map { it.condition(predicates) }
        return delete.`$using`(using).`$where`(and(delete.`$where`(), conditions))
    }

    fun insert(insert: QOM.Insert<*>): QOM.Insert<*> {
        val target = writeTarget(insert.`$into`(), "INSERT") ?: return insert
        var result: QOM.Insert<*> = insert
        val columns = insert.`$columns`()
        val tenantIndex = columns.indexOfFirst { isTenantColumn(it) }
        val select = insert.`$select`()
        when {
            select != null && tenantIndex >= 0 -> {
                throw TenantPredicateViolation(
                    "${target.base.qualifiedName}: an INSERT ... SELECT names ${TenancyTables.TENANT_COLUMN}; " +
                        "omit it, tenancy-jooq sets it to the bound tenant",
                )
            }

            select != null -> {
                result =
                    result
                        .`$columns`(columns + target.tenantField())
                        .`$select`(
                            DSL
                                .select(DSL.asterisk(), predicates.tenantValue())
                                .from(select.asTable(DSL.name("tenancy_jooq_source"))),
                        )
            }

            columns.isEmpty() && insert.`$values`().isNotEmpty() -> {
                throw TenantPredicateViolation(
                    "${target.base.qualifiedName}: an INSERT names no columns; name the columns",
                )
            }

            columns.isEmpty() -> {
                result =
                    result
                        .`$columns`(listOf(target.tenantField()))
                        .`$values`(listOf(DSL.row(predicates.tenantValue())))
            }

            tenantIndex >= 0 -> {
                insert.`$values`().forEach { row -> requireTenantValue(row, tenantIndex, target) }
            }

            else -> {
                result =
                    result
                        .`$columns`(columns + target.tenantField())
                        .`$values`(
                            insert.`$values`().map { row ->
                                DSL.row(row.`$fields`() + predicates.tenantValue())
                            },
                        )
            }
        }
        if (insert.`$updateSet`().isNotEmpty()) {
            refuseTenantColumnSet(insert.`$updateSet`().keys, "an ON CONFLICT DO UPDATE")
            result = result.`$updateWhere`(and(insert.`$updateWhere`(), listOf(predicates.write(target))))
        }
        return result
    }

    fun merge(merge: QOM.Merge<*>): QOM.Merge<*> {
        val target = writeTarget(merge.`$into`(), "MERGE") ?: return refusePlatformScopedSource(merge)
        val source = sourceConditions(mergeSource(merge))
        val matched =
            merge.`$whenMatched`().map { clause ->
                refuseTenantColumnSet(clause.`$updateSet`().keys, "a MERGE WHEN MATCHED")
                clause.`$where`(and(clause.`$where`(), source))
            }
        val notMatched =
            merge.`$whenNotMatched`().map { clause ->
                val columns = clause.`$columns`()
                val tenantIndex = columns.indexOfFirst { isTenantColumn(it) }
                val withTenant =
                    when {
                        columns.isEmpty() -> {
                            throw TenantPredicateViolation(
                                "${target.base.qualifiedName}: a MERGE WHEN NOT MATCHED names no columns",
                            )
                        }

                        tenantIndex >= 0 -> {
                            clause.`$values`().forEach { row -> requireTenantValue(row, tenantIndex, target) }
                            clause
                        }

                        else -> {
                            clause
                                .`$columns`(columns + target.tenantField())
                                .`$values`(
                                    clause.`$values`().map {
                                        DSL.row(it.`$fields`() + predicates.tenantValue())
                                    },
                                )
                        }
                    }
                withTenant.`$where`(and(clause.`$where`(), source))
            }
        if (merge.`$whenNotMatchedBySource`().isNotEmpty()) {
            // Every target row outside the bound tenant is "not matched by source"; jOOQ 3.21's clause model cannot
            // carry the target predicate into it, so the clause is refused.
            throw TenantPredicateViolation(
                "MERGE WHEN NOT MATCHED BY SOURCE on ${target.base.qualifiedName} is refused",
            )
        }
        return merge
            .`$on`(and(merge.`$on`(), listOf(predicates.write(target))))
            .`$whenMatched`(matched)
            .`$whenNotMatched`(notMatched)
    }

    private fun refusePlatformScopedSource(merge: QOM.Merge<*>): QOM.Merge<*> {
        // A PLATFORM_SCOPED target (written only under the platform binding): its source still reads tenant rows
        // only through their predicates.
        val source = sourceConditions(mergeSource(merge))
        if (source.isEmpty()) return merge
        return merge
            .`$whenMatched`(merge.`$whenMatched`().map { it.`$where`(and(it.`$where`(), source)) })
            .`$whenNotMatched`(merge.`$whenNotMatched`().map { it.`$where`(and(it.`$where`(), source)) })
    }

    private fun mergeSource(merge: QOM.Merge<*>): TableLike<*> =
        merge.`$using`() ?: throw TenantPredicateViolation(
            "a MERGE without a source",
        )

    private fun sourceConditions(using: TableLike<*>): List<Condition> {
        if (using !is Table<*>) return emptyList() // a subquery: rewritten when it is visited itself
        val (_, pending) = rewriteTable(using)
        if (using is QOM.JoinTable<*, *>) {
            throw TenantPredicateViolation("a join as a MERGE source; use a subquery")
        }
        return pending.map { it.condition(predicates) }
    }

    /**
     * The data table a write targets, after the write rules: null for a PLATFORM_SCOPED table (no predicate), which
     * only the platform binding writes; a REFERENCE table is never written under the platform binding (its platform
     * rows are written by a definer function, INV-ADR-3968 item 6).
     */
    private fun writeTarget(
        table: Table<*>,
        statement: String,
    ): DataRef? {
        val ref =
            when (val classified = tables.classify(table)) {
                is DataRef -> classified
                NoData -> throw TenantPredicateViolation("$statement of a table that is not in the schema: $table")
            }
        return when (ref.tableClass) {
            TableClass.PLATFORM_SCOPED -> {
                if (!tenant.isPlatform) {
                    throw TenantPredicateViolation(
                        "$statement of the PLATFORM_SCOPED table ${ref.base.qualifiedName} under tenant $tenant; " +
                            "only the platform-tenant binding writes it",
                    )
                }
                null
            }

            TableClass.REFERENCE -> {
                if (tenant.isPlatform) {
                    throw TenantPredicateViolation(
                        "$statement of the REFERENCE table ${ref.base.qualifiedName} under the platform binding; " +
                            "a platform row is never written through jOOQ",
                    )
                }
                ref
            }

            TableClass.TENANT -> {
                ref
            }
        }
    }

    private fun requireTenantValue(
        row: Row,
        index: Int,
        target: DataRef,
    ) {
        val value = row.`$fields`().getOrNull(index)
        if (value == null || !isTenantValue(value, tenant)) {
            throw TenantPredicateViolation(
                "a write of ${target.base.qualifiedName} sets ${TenancyTables.TENANT_COLUMN} to $value, " +
                    "not the bound tenant",
            )
        }
    }

    private fun refuseTenantColumnSet(
        targets: Collection<FieldOrRow>,
        statement: String,
    ) {
        val fields = targets.flatMap { if (it is Row) it.`$fields`() else listOf(it as Field<*>) }
        if (fields.any { isTenantColumn(it) }) {
            throw TenantPredicateViolation("$statement that sets ${TenancyTables.TENANT_COLUMN} is refused")
        }
    }

    /** Rewrites a FROM (or UPDATE FROM, DELETE USING) list; the pending predicates belong in its WHERE. */
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

    private fun rewriteTable(table: Table<*>): Pair<Table<*>, List<Pending>> {
        if (table !is QOM.JoinTable<*, *>) {
            return when (val ref = tables.classify(table)) {
                is DataRef -> {
                    val pending =
                        if (ref.tableClass == TableClass.PLATFORM_SCOPED) emptyList() else listOf(Pending(ref, false))
                    table to pending
                }

                NoData -> {
                    table to emptyList()
                }
            }
        }
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

    private fun withOn(
        join: QOM.JoinTable<*, *>,
        pending: List<Pending>,
    ): Table<*> {
        if (pending.isEmpty()) return join
        val qualified = join as QOM.QualifiedJoin<*, *>
        return qualified.`$on`(and(qualified.`$on`(), pending.map { it.condition(predicates) }))
    }

    private fun refuseNullableData(
        join: QueryPart,
        pending: List<Pending>,
    ) {
        if (pending.isNotEmpty()) {
            throw TenantPredicateViolation(
                "an outer join without ON (NATURAL, USING or OUTER APPLY) whose nullable side reads " +
                    "${pending.map { it.ref.base.qualifiedName }}; write the join with ON: $join",
            )
        }
    }

    private fun and(
        existing: Condition?,
        added: List<Condition>,
    ): Condition = if (added.isEmpty()) existing ?: DSL.noCondition() else DSL.and(listOfNotNull(existing) + added)
}
