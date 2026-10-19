package com.dynokore.platform.tenancyjooq

import org.jooq.Condition
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
    tables: TenancyTables,
    private val predicates: Predicates,
) {
    private val joins = JoinRewriter(tables, predicates)
    private val writes = WriteRules(tables, predicates)

    fun select(select: Select<*>): Select<*> {
        val (from, pending) = joins.rewriteTables(select.`$from`())
        val unchanged = from.indices.all { from[it] === select.`$from`()[it] }
        if (pending.isEmpty() && unchanged) return select
        return select.`$from`(from).`$where`(andConditions(select.`$where`(), pending.map { it.condition(predicates) }))
    }

    fun update(update: QOM.Update<*>): QOM.Update<*> {
        val target = writes.target(update.`$table`(), "UPDATE")
        writes.refuseTenantColumnSet(update.`$set`().keys, "an UPDATE")
        val (from, pending) = joins.rewriteTables(update.`$from`())
        val conditions = listOfNotNull(target?.let { predicates.write(it) }) + pending.map { it.condition(predicates) }
        return update.`$from`(from).`$where`(andConditions(update.`$where`(), conditions))
    }

    fun delete(delete: QOM.Delete<*>): QOM.Delete<*> {
        val target = writes.target(delete.`$from`(), "DELETE")
        val (using, pending) = joins.rewriteTables(delete.`$using`())
        val conditions = listOfNotNull(target?.let { predicates.write(it) }) + pending.map { it.condition(predicates) }
        return delete.`$using`(using).`$where`(andConditions(delete.`$where`(), conditions))
    }

    fun insert(insert: QOM.Insert<*>): QOM.Insert<*> {
        val target = writes.target(insert.`$into`(), "INSERT") ?: return insert
        val result = withTenantValue(insert, target)
        return if (insert.`$updateSet`().isEmpty()) result else onConflictUpdate(insert, result, target)
    }

    private fun onConflictUpdate(
        insert: QOM.Insert<*>,
        result: QOM.Insert<*>,
        target: DataRef,
    ): QOM.Insert<*> {
        writes.refuseTenantColumnSet(insert.`$updateSet`().keys, "an ON CONFLICT DO UPDATE")
        return result.`$updateWhere`(andConditions(insert.`$updateWhere`(), listOf(predicates.write(target))))
    }

    private fun withTenantValue(
        insert: QOM.Insert<*>,
        target: DataRef,
    ): QOM.Insert<*> {
        val columns = insert.`$columns`()
        val tenantIndex = columns.indexOfFirst { isTenantColumn(it) }
        val select = insert.`$select`()
        return when {
            select != null && tenantIndex >= 0 -> {
                refuse(
                    "${target.base.qualifiedName}: an INSERT ... SELECT names ${TenancyTables.TENANT_COLUMN}; " +
                        "omit it, tenancy-jooq sets it to the bound tenant",
                )
            }

            select != null -> {
                insert
                    .`$columns`(columns + target.tenantField())
                    .`$select`(
                        DSL
                            .select(DSL.asterisk(), predicates.tenantValue())
                            .from(select.asTable(DSL.name("tenancy_jooq_source"))),
                    )
            }

            columns.isEmpty() && insert.`$values`().isNotEmpty() -> {
                refuse("${target.base.qualifiedName}: an INSERT names no columns; name the columns")
            }

            columns.isEmpty() -> {
                insert
                    .`$columns`(listOf(target.tenantField()))
                    .`$values`(listOf(DSL.row(predicates.tenantValue())))
            }

            tenantIndex >= 0 -> {
                insert.`$values`().forEach { row -> writes.requireTenantValue(row, tenantIndex, target) }
                insert
            }

            else -> {
                insert
                    .`$columns`(columns + target.tenantField())
                    .`$values`(insert.`$values`().map { row -> DSL.row(row.`$fields`() + predicates.tenantValue()) })
            }
        }
    }

    fun merge(merge: QOM.Merge<*>): QOM.Merge<*> {
        val target = writes.target(merge.`$into`(), "MERGE") ?: return refusePlatformScopedSource(merge)
        val source = sourceConditions(mergeSource(merge))
        val matched =
            merge.`$whenMatched`().map { clause ->
                writes.refuseTenantColumnSet(clause.`$updateSet`().keys, "a MERGE WHEN MATCHED")
                clause.`$where`(andConditions(clause.`$where`(), source))
            }
        val notMatched = merge.`$whenNotMatched`().map { mergeNotMatched(it, target, source) }
        if (merge.`$whenNotMatchedBySource`().isNotEmpty()) {
            // Every target row outside the bound tenant is "not matched by source"; jOOQ 3.21's clause model cannot
            // carry the target predicate into it, so the clause is refused.
            refuse("MERGE WHEN NOT MATCHED BY SOURCE on ${target.base.qualifiedName} is refused")
        }
        return merge
            .`$on`(andConditions(merge.`$on`(), listOf(predicates.write(target))))
            .`$whenMatched`(matched)
            .`$whenNotMatched`(notMatched)
    }

    private fun mergeNotMatched(
        clause: QOM.MergeNotMatched,
        target: DataRef,
        source: List<Condition>,
    ): QOM.MergeNotMatched {
        val columns = clause.`$columns`()
        val tenantIndex = columns.indexOfFirst { isTenantColumn(it) }
        val withTenant =
            when {
                columns.isEmpty() -> {
                    refuse("${target.base.qualifiedName}: a MERGE WHEN NOT MATCHED names no columns")
                }

                tenantIndex >= 0 -> {
                    clause.`$values`().forEach { row -> writes.requireTenantValue(row, tenantIndex, target) }
                    clause
                }

                else -> {
                    clause
                        .`$columns`(columns + target.tenantField())
                        .`$values`(clause.`$values`().map { DSL.row(it.`$fields`() + predicates.tenantValue()) })
                }
            }
        return withTenant.`$where`(andConditions(clause.`$where`(), source))
    }

    private fun refusePlatformScopedSource(merge: QOM.Merge<*>): QOM.Merge<*> {
        // A PLATFORM_SCOPED target (written only under the platform binding): its source still reads tenant rows
        // only through their predicates.
        val source = sourceConditions(mergeSource(merge))
        if (source.isEmpty()) return merge
        return merge
            .`$whenMatched`(merge.`$whenMatched`().map { it.`$where`(andConditions(it.`$where`(), source)) })
            .`$whenNotMatched`(merge.`$whenNotMatched`().map { it.`$where`(andConditions(it.`$where`(), source)) })
    }

    private fun mergeSource(merge: QOM.Merge<*>): TableLike<*> = merge.`$using`() ?: refuse("a MERGE without a source")

    private fun sourceConditions(using: TableLike<*>): List<Condition> {
        if (using !is Table<*>) return emptyList() // a subquery: rewritten when it is visited itself
        val (_, pending) = joins.rewriteTable(using)
        if (using is QOM.JoinTable<*, *>) refuse("a join as a MERGE source; use a subquery")
        return pending.map { it.condition(predicates) }
    }
}
