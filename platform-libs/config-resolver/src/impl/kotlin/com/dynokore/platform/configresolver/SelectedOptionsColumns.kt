package com.dynokore.platform.configresolver

import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * The two columns a value is stored in: `option_keys text[]` (catalogue order of choice) and
 * `option_values jsonb`, an object from option key to that option's typed value, absent when no chosen
 * option is typed (INV-Q-2934).
 */
internal class SelectedOptionsColumns(
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    fun bind(
        statement: PreparedStatement,
        connection: Connection,
        keysIndex: Int,
        valuesIndex: Int,
        selected: List<SelectedOption>,
    ) {
        statement.setArray(keysIndex, connection.createArrayOf("text", selected.map { it.key }.toTypedArray()))
        val typed = selected.filter { it.valueJson != null }
        if (typed.isEmpty()) {
            statement.setNull(valuesIndex, Types.OTHER)
        } else {
            val node = mapper.createObjectNode()
            typed.forEach { node.set(it.key, mapper.readTree(it.valueJson)) }
            statement.setObject(valuesIndex, mapper.writeValueAsString(node), Types.OTHER)
        }
    }

    fun read(rows: ResultSet): List<SelectedOption> {
        val keys = (rows.getArray("option_keys").array as Array<*>).map { it as String }
        val values = rows.getString("option_values")?.let { mapper.readTree(it) }
        return keys.map { key -> SelectedOption(key, values?.get(key)?.let { mapper.writeValueAsString(it) }) }
    }
}
