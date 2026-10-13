package com.dynokore.platform.tenancyjooq

import org.jooq.Name

/**
 * The tables tenancy-jooq knows, each with its [TableClass], keyed by qualified name (`schema.table`). A table that is
 * not here is refused (TenantPredicateViolation: not in the generated schema).
 *
 * Built either from a generated schema by [TableClasses.verify], or, for a platform-libs module that names its tables
 * with `DSL.name(...)`, by [declared]. The tenant column of a [TableClass.TENANT] or [TableClass.REFERENCE] table is
 * always `tenant_id` (CS-19).
 */
class TenancyTables private constructor(
    private val classes: Map<List<String>, TableClass>,
) {
    /** The class of the table with this qualified name, or null when the table is not known. */
    fun classOf(qualifiedName: Name): TableClass? = classes[key(qualifiedName)]

    /** The qualified names (`schema.table`) of the known tables, sorted. */
    val names: List<String> get() = classes.keys.map { it.joinToString(".") }.sorted()

    companion object {
        /** The tenant column of every [TableClass.TENANT] and [TableClass.REFERENCE] table (CS-19). */
        const val TENANT_COLUMN = "tenant_id"

        /**
         * The tables of a platform-libs module, named with `DSL.name(schema, table)` and declared with their class
         * (CS-18: platform-libs modules use jOOQ's DSL with `DSL.name(...)` identifiers on tenancy-jooq's DSLContext).
         *
         * @throws IllegalArgumentException when a name is not qualified by its schema, or is declared twice.
         */
        fun declared(tables: Map<Name, TableClass>): TenancyTables {
            val keyed = tables.entries.map { (name, tableClass) -> key(name) to tableClass }
            keyed.forEach { (name, _) ->
                require(name.size == 2 && name.all { it.isNotEmpty() }) {
                    "a table is declared by its schema-qualified name (schema, table): ${name.joinToString(".")}"
                }
            }
            val duplicates = keyed.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "tables declared twice: ${duplicates.map { it.joinToString(".") }}" }
            return TenancyTables(keyed.toMap())
        }

        internal fun of(classes: Map<List<String>, TableClass>): TenancyTables = TenancyTables(classes.toMap())

        internal fun key(name: Name): List<String> = name.name.toList()
    }
}
