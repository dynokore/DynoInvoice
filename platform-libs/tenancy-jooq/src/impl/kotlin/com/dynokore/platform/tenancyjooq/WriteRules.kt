package com.dynokore.platform.tenancyjooq

import org.jooq.Field
import org.jooq.FieldOrRow
import org.jooq.Row
import org.jooq.Table

/** The write rules of the injector's rewrite: which table a write targets, and what a write may not set. */
internal class WriteRules(
    private val tables: TenancyTables,
    private val predicates: Predicates,
) {
    private val tenant get() = predicates.tenant

    /**
     * The data table a write targets, after the write rules: null for a PLATFORM_SCOPED table (no predicate), which
     * only the platform binding writes; a REFERENCE table is never written under the platform binding (its platform
     * rows are written by a definer function, INV-ADR-3968 item 6).
     */
    fun target(
        table: Table<*>,
        statement: String,
    ): DataRef? {
        val ref =
            when (val classified = tables.classify(table)) {
                is DataRef -> classified
                NoData -> refuse("$statement of a table that is not in the schema: $table")
            }
        return when (ref.tableClass) {
            TableClass.PLATFORM_SCOPED -> {
                if (!tenant.isPlatform) {
                    refuse(
                        "$statement of the PLATFORM_SCOPED table ${ref.base.qualifiedName} under tenant $tenant; " +
                            "only the platform-tenant binding writes it",
                    )
                }
                null
            }

            TableClass.REFERENCE -> {
                if (tenant.isPlatform) {
                    refuse(
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

    fun requireTenantValue(
        row: Row,
        index: Int,
        target: DataRef,
    ) {
        val value = row.`$fields`().getOrNull(index)
        if (value == null || !isTenantValue(value, tenant)) {
            refuse(
                "a write of ${target.base.qualifiedName} sets ${TenancyTables.TENANT_COLUMN} to $value, " +
                    "not the bound tenant",
            )
        }
    }

    fun refuseTenantColumnSet(
        targets: Collection<FieldOrRow>,
        statement: String,
    ) {
        val fields = targets.flatMap { if (it is Row) it.`$fields`() else listOf(it as Field<*>) }
        if (fields.any { isTenantColumn(it) }) {
            refuse("$statement that sets ${TenancyTables.TENANT_COLUMN} is refused")
        }
    }
}
