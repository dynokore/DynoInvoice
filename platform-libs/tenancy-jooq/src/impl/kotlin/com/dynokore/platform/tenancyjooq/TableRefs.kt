package com.dynokore.platform.tenancyjooq

import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.CommonTableExpression
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Param
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.QOM
import org.jooq.impl.SQLDataType
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

/** What a table reference in a statement is, for tenancy-jooq (shared by the injector and the check). */
internal sealed interface TableRef

/**
 * A table of the known schema as the statement declares it: [declared] is the alias or the table itself, [base] the
 * table of the schema, [qualifier] the name its columns are qualified with (the alias, else `schema.table`).
 */
internal class DataRef(
    val declared: Table<*>,
    val base: Table<*>,
    val tableClass: TableClass,
    val qualifier: List<String>,
) : TableRef {
    /** The `tenant_id` column as this reference qualifies it. */
    fun tenantField(): Field<UUID> = DSL.field(DSL.name(qualifier + TenancyTables.TENANT_COLUMN), SQLDataType.UUID)
}

/** A table that holds no rows of its own: a derived table, a common table expression, VALUES, DUAL. */
internal object NoData : TableRef

/** The table kinds that carry no data of their own; every other table must be a known table of the schema. */
internal fun TenancyTables.classify(table: Table<*>): TableRef =
    when (table) {
        is QOM.TableAlias<*> -> {
            when (val inner = classify(table.`$table`())) {
                is DataRef -> DataRef(table, inner.base, inner.tableClass, table.`$alias`().name.toList())
                NoData -> NoData
            }
        }

        is QOM.DerivedTable<*>, is CommonTableExpression<*>, is QOM.Values<*>, is QOM.Dual -> {
            NoData
        }

        is QOM.Lateral<*> -> {
            classify(table.`$arg1`())
        }

        is QOM.JoinTable<*, *> -> {
            throw TenantPredicateViolation("a join is not a table reference: $table")
        }

        else -> {
            val tableClass =
                classOf(table.qualifiedName)
                    ?: throw TenantPredicateViolation(
                        "the table ${table.qualifiedName} is not in the generated schema (TableClasses.verify)",
                    )
            DataRef(table, table, tableClass, table.qualifiedName.name.toList())
        }
    }

/** A table of its own (not an alias, join, derived table, CTE, VALUES, DUAL or LATERAL wrapper). */
internal fun isBaseTable(part: Any): Boolean =
    part is Table<*> &&
        part !is QOM.TableAlias<*> &&
        part !is QOM.JoinTable<*, *> &&
        part !is QOM.DerivedTable<*> &&
        part !is CommonTableExpression<*> &&
        part !is QOM.Values<*> &&
        part !is QOM.Dual &&
        part !is QOM.Lateral<*>

/** True when [field] names the tenant column (an INSERT column, an UPDATE SET target). */
internal fun isTenantColumn(field: Field<*>): Boolean = field.unqualifiedName.last() == TenancyTables.TENANT_COLUMN

/** True when [field] is a bind or inline value equal to [tenant]. */
internal fun isTenantValue(
    field: Field<*>,
    tenant: TenantId,
): Boolean {
    val value = if (field is QOM.FieldAlias<*>) field.`$field`() else field
    return value is Param<*> && value.value?.toString() == tenant.toString()
}

/** The conjuncts of [condition]: its AND operands, recursively; none for no condition. */
internal fun conjuncts(condition: Condition?): List<Condition> =
    when (condition) {
        null -> emptyList()
        is QOM.And -> conjuncts(condition.`$arg1`()) + conjuncts(condition.`$arg2`())
        else -> listOf(condition)
    }

internal fun <T> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap())

/** The join kinds, by where a predicate of each side goes. */
internal enum class JoinKind {
    /** Inner, cross, natural inner, straight, CROSS APPLY: both sides take the enclosing predicate place. */
    INNER,

    /** Left outer, left semi, left anti: the right side is nullable (or tested), its predicate goes into ON. */
    LEFT,

    /** Right outer: the left side is nullable, its predicate goes into ON. */
    RIGHT,

    /** Full outer: both sides nullable; predicates go into ON, and a null-tolerant form into the enclosing place. */
    FULL,

    /** An outer join without an ON clause (NATURAL outer, USING, OUTER APPLY): its nullable side may hold no data. */
    LEFT_NO_ON,
    RIGHT_NO_ON,
    FULL_NO_ON,
}

internal fun joinKind(join: QOM.JoinTable<*, *>): JoinKind {
    val usingOnly = join is QOM.QualifiedJoin<*, *> && join.`$using`().isNotEmpty()
    return when (join) {
        is QOM.Join<*>, is QOM.StraightJoin<*>, is QOM.CrossJoin<*>, is QOM.NaturalJoin<*>, is QOM.CrossApply<*> -> {
            JoinKind.INNER
        }

        is QOM.LeftJoin<*>, is QOM.LeftSemiJoin<*>, is QOM.LeftAntiJoin<*> -> {
            if (usingOnly) JoinKind.LEFT_NO_ON else JoinKind.LEFT
        }

        is QOM.RightJoin<*> -> {
            if (usingOnly) JoinKind.RIGHT_NO_ON else JoinKind.RIGHT
        }

        is QOM.FullJoin<*> -> {
            if (usingOnly) JoinKind.FULL_NO_ON else JoinKind.FULL
        }

        is QOM.NaturalLeftJoin<*>, is QOM.OuterApply<*> -> {
            JoinKind.LEFT_NO_ON
        }

        is QOM.NaturalRightJoin<*> -> {
            JoinKind.RIGHT_NO_ON
        }

        is QOM.NaturalFullJoin<*> -> {
            JoinKind.FULL_NO_ON
        }

        else -> {
            throw TenantPredicateViolation("an unsupported join kind: $join")
        }
    }
}
