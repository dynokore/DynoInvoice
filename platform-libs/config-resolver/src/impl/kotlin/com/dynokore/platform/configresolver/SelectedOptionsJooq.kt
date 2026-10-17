package com.dynokore.platform.configresolver

import org.jooq.Field
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.databind.ObjectMapper

/**
 * The two columns a value is stored in, `option_keys text[]` (catalogue order of
 * choice) and `option_values jsonb`, an object from option key to that option's typed value, absent when no chosen
 * option is typed (INV-Q-2934).
 */
internal class SelectedOptionsJooq(
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    val keys: Field<Array<String>> = DSL.field(DSL.name("option_keys"), SQLDataType.CLOB.array())
    val values: Field<JSONB> = DSL.field(DSL.name("option_values"), SQLDataType.JSONB)

    fun keysOf(selected: List<SelectedOption>): Array<String> = selected.map { it.key }.toTypedArray()

    /** The `option_values` value to write: an object of the typed options' values as a jsonb bind value, or null. */
    fun valuesOf(selected: List<SelectedOption>): Field<JSONB> {
        val typed = selected.filter { it.valueJson != null }
        if (typed.isEmpty()) return DSL.`val`(null, SQLDataType.JSONB)
        val node = mapper.createObjectNode()
        typed.forEach { node.set(it.key, mapper.readTree(it.valueJson)) }
        return DSL.`val`(mapper.writeValueAsString(node)).cast(SQLDataType.JSONB)
    }

    fun read(
        keys: Array<String>,
        values: JSONB?,
    ): List<SelectedOption> {
        val tree = values?.let { mapper.readTree(it.data()) }
        return keys.map { key -> SelectedOption(key, tree?.get(key)?.let { mapper.writeValueAsString(it) }) }
    }
}
