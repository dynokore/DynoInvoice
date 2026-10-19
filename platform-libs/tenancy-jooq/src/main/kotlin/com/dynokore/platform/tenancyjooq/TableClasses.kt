package com.dynokore.platform.tenancyjooq

import org.jooq.Schema
import org.jooq.Table

/**
 * A service's declaration of its [TableClass.PLATFORM_SCOPED] and [TableClass.REFERENCE] tables, by qualified name
 * (`schema.table`); every other table with a `tenant_id` column is [TableClass.TENANT] (INV-ADR-3968 item 4a).
 *
 * [verify] checks the declaration against the jOOQ schema generated from the service's migrations and fails start-up
 * when they disagree, so a table can never reach a query unclassified.
 */
class TableClasses private constructor(
    private val declared: Map<List<String>, TableClass>,
) {
    /**
     * Checks this declaration against [schemas] (the generated jOOQ schemas of the service) and returns the classified
     * tables.
     *
     * @throws IllegalStateException (start-up fails) when a table is undeclared and has no tenant column, when a
     *   declared [TableClass.PLATFORM_SCOPED] table has a tenant column, when a declared [TableClass.REFERENCE] table
     *   has none, or when a declared table is missing from [schemas].
     */
    fun verify(vararg schemas: Schema): TenancyTables = verify(schemas.toList())

    /** Same check as the vararg overload, for a caller that already holds the schemas as a list. */
    fun verify(schemas: List<Schema>): TenancyTables {
        val problems = mutableListOf<String>()
        val classes = linkedMapOf<List<String>, TableClass>()
        schemas.flatMap { it.tables }.forEach { table ->
            val name = TenancyTables.key(table.qualifiedName)
            val hasTenantColumn = hasTenantColumn(table)
            val tableClass =
                when (val declaredClass = declared[name]) {
                    null -> {
                        if (!hasTenantColumn) {
                            problems += "${display(name)} has no ${TenancyTables.TENANT_COLUMN} column and is not " +
                                "declared PLATFORM_SCOPED"
                        }
                        TableClass.TENANT
                    }

                    TableClass.PLATFORM_SCOPED -> {
                        if (hasTenantColumn) {
                            problems += "${display(name)} is declared PLATFORM_SCOPED but has a " +
                                "${TenancyTables.TENANT_COLUMN} column"
                        }
                        declaredClass
                    }

                    else -> {
                        if (!hasTenantColumn) {
                            problems += "${display(name)} is declared $declaredClass but has no " +
                                "${TenancyTables.TENANT_COLUMN} column"
                        }
                        declaredClass
                    }
                }
            classes[name] = tableClass
        }
        declared.keys.filterNot { it in classes }.forEach { problems += "${display(it)} is declared but missing" }
        check(problems.isEmpty()) {
            "TableClasses.verify (INV-ADR-3968 item 4a): ${problems.joinToString("; ")}"
        }
        return TenancyTables.of(classes)
    }

    private fun hasTenantColumn(table: Table<*>): Boolean = table.field(TenancyTables.TENANT_COLUMN) != null

    private fun display(name: List<String>): String = name.joinToString(".")

    companion object {
        /**
         * Declares the [platformScoped] and [reference] tables by qualified name (`schema.table`).
         *
         * @throws IllegalArgumentException when a name is not `schema.table` or is declared twice.
         */
        fun of(
            platformScoped: Collection<String> = emptyList(),
            reference: Collection<String> = emptyList(),
        ): TableClasses {
            val entries =
                platformScoped.map { parse(it) to TableClass.PLATFORM_SCOPED } +
                    reference.map { parse(it) to TableClass.REFERENCE }
            val duplicates = entries.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "tables declared twice: ${duplicates.map { it.joinToString(".") }}" }
            return TableClasses(entries.toMap())
        }

        private fun parse(qualifiedName: String): List<String> {
            val parts = qualifiedName.split('.')
            require(parts.size == 2 && parts.all { it.isNotEmpty() }) {
                "a table is declared by its qualified name schema.table: '$qualifiedName'"
            }
            return parts
        }
    }
}
