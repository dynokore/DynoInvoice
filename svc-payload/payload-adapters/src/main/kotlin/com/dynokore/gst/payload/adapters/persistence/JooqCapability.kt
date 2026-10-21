package com.dynokore.gst.payload.adapters.persistence

import com.dynokore.gst.payload.adapters.persistence.jooq.tables.Capability
import com.dynokore.gst.payload.application.BindingTableInspector
import com.dynokore.gst.payload.application.CapabilityRegistry
import com.dynokore.gst.payload.domain.BindingTableRef
import com.dynokore.gst.payload.domain.CapabilityKey
import com.dynokore.gst.payload.domain.CapabilityState
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** The capability registry over the generated `payload.capability` table. */
class JooqCapabilityRegistry(
    private val dsl: DSLContext,
) : CapabilityRegistry {
    override fun state(key: CapabilityKey): CapabilityState =
        dsl
            .select(Capability.CAPABILITY.STATE)
            .from(Capability.CAPABILITY)
            .where(Capability.CAPABILITY.KEY.eq(key.name))
            .fetchOne(Capability.CAPABILITY.STATE)
            ?.let(CapabilityState::valueOf)
            ?: error("capability ${key.name} is missing from payload.capability")

    override fun markSpecified(key: CapabilityKey) {
        val updated =
            dsl
                .update(Capability.CAPABILITY)
                .set(Capability.CAPABILITY.STATE, CapabilityState.SPECIFIED.name)
                .where(Capability.CAPABILITY.KEY.eq(key.name))
                .execute()
        check(updated == 1) { "capability ${key.name} is missing from payload.capability" }
    }
}

/**
 * Reads the binding table through jOOQ names, never a SQL string. The table is found by exact schema and table
 * name in the catalogue, so `mde_binding_tables` is not `mde_binding_table`.
 */
class JooqBindingTableInspector(
    private val dsl: DSLContext,
) : BindingTableInspector {
    override fun exists(table: BindingTableRef): Boolean {
        val tables = DSL.table(DSL.name("information_schema", "tables"))
        return dsl.fetchExists(
            tables.where(
                DSL.field(DSL.name("table_schema"), String::class.java).eq(table.schema).and(
                    DSL.field(DSL.name("table_name"), String::class.java).eq(table.table),
                ),
            ),
        )
    }

    override fun rows(table: BindingTableRef): Int = dsl.fetchCount(dsl.selectFrom(source(table)))

    override fun incompleteRows(table: BindingTableRef): Int {
        val missing =
            listOf(table.businessTermIdColumn, table.pintSgCardinalityColumn, table.irasMdeStatusColumn)
                .map { column ->
                    val value = DSL.field(DSL.name(table.schema, table.table, column)).cast(String::class.java)
                    value.isNull.or(DSL.trim(value).eq(""))
                }
        return dsl.fetchCount(dsl.selectFrom(source(table)).where(DSL.or(missing)))
    }

    private fun source(table: BindingTableRef) = DSL.table(DSL.name(table.schema, table.table))
}
