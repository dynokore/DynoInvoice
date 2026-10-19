package com.dynokore.platform.tenancyjooq

import org.jooq.Condition
import org.jooq.Field
import org.jooq.FieldOrRow
import org.jooq.Row
import org.jooq.Table
import org.jooq.TableLike
import org.jooq.impl.QOM

/** The write half of [TenantPredicateCheck]: the write rules and the tenant value of INSERT and MERGE. */
internal class WriteChecks(
    private val tables: TenancyTables,
    private val predicates: Predicates,
    private val placement: PlacementChecks,
) {
    /** The data table a write targets, re-checking the write rules; null for a PLATFORM_SCOPED table. */
    fun target(table: Table<*>): DataRef? {
        val ref = tables.classify(table) as? DataRef ?: refuse("a write of $table")
        val platform = predicates.tenant.isPlatform
        return when (ref.tableClass) {
            TableClass.PLATFORM_SCOPED -> {
                if (!platform) refuse("a PLATFORM_SCOPED write under a tenant")
                null
            }

            TableClass.REFERENCE -> {
                if (platform) refuse("a REFERENCE write under the platform binding")
                ref
            }

            TableClass.TENANT -> {
                ref
            }
        }
    }

    fun requireNoTenantColumnSet(targets: Collection<FieldOrRow>) {
        val fields = targets.flatMap { if (it is Row) it.`$fields`() else listOf(it as Field<*>) }
        if (fields.any { isTenantColumn(it) }) refuse("a write that sets the tenant column")
    }

    fun insert(insert: QOM.Insert<*>) {
        val target = target(insert.`$into`()) ?: return
        val index = insert.`$columns`().indexOfFirst { isTenantColumn(it) }
        if (index < 0) refuse("${target.base.qualifiedName}: an INSERT without the tenant")
        val select = insert.`$select`()
        val values =
            if (select != null) {
                // INSERT ... SELECT: the injector appends the tenant as the last column and the last select item.
                if (index != insert.`$columns`().lastIndex) listOf(null) else listOf(select.`$select`().lastOrNull())
            } else {
                insert.`$values`().map { it.`$fields`().getOrNull(index) }
            }
        if (values.isEmpty() || values.any { it !is Field<*> || !isTenantValue(it, predicates.tenant) }) {
            refuse("${target.base.qualifiedName}: an INSERT not set to the bound tenant")
        }
        if (insert.`$updateSet`().isNotEmpty()) {
            requireNoTenantColumnSet(insert.`$updateSet`().keys)
            placement.requireCondition(
                target,
                predicates.write(target),
                conjuncts(insert.`$updateWhere`()),
                count = false,
            )
        }
        placement.verify(target.base)
    }

    fun merge(merge: QOM.Merge<*>) {
        val target = target(merge.`$into`())
        val source = sourceOf(merge.`$using`() ?: refuse("a MERGE without a source"))
        merge.`$whenMatched`().forEach { clause ->
            requireNoTenantColumnSet(clause.`$updateSet`().keys)
            requireSource(source, clause.`$where`())
        }
        merge.`$whenNotMatched`().forEach { clause ->
            requireSource(source, clause.`$where`())
            if (target != null) requireMergeInsert(target, clause)
        }
        if (target != null) {
            if (merge.`$whenNotMatchedBySource`().isNotEmpty()) {
                refuse("MERGE WHEN NOT MATCHED BY SOURCE on ${target.base.qualifiedName}")
            }
            placement.requireCondition(target, predicates.write(target), conjuncts(merge.`$on`()))
        }
        source.forEach { placement.verify(it.ref.base) }
    }

    private fun requireSource(
        source: List<Pending>,
        where: Condition?,
    ) = source.forEach {
        placement.requireCondition(it.ref, it.condition(predicates), conjuncts(where), count = false)
    }

    private fun requireMergeInsert(
        target: DataRef,
        clause: QOM.MergeNotMatched,
    ) {
        val index = clause.`$columns`().indexOfFirst { isTenantColumn(it) }
        val values = clause.`$values`().map { it.`$fields`().getOrNull(index) }
        val tenantSet =
            index >= 0 && values.isNotEmpty() && values.all { it != null && isTenantValue(it, predicates.tenant) }
        if (!tenantSet) refuse("${target.base.qualifiedName}: a MERGE insert not set to the bound tenant")
    }

    private fun sourceOf(using: TableLike<*>): List<Pending> {
        if (using !is Table<*>) return emptyList()
        if (using is QOM.JoinTable<*, *>) refuse("a join as a MERGE source")
        return placement.pendingOf(listOf(using))
    }
}
