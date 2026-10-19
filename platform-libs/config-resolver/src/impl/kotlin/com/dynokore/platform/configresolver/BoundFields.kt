package com.dynokore.platform.configresolver

import com.dynokore.platform.configresolver.ScopeBounds.Field
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal

/** The number fields of a selection that a row directs or bounds, and their JSON (split out of [ScopeBounds]). */
internal class BoundFields(
    private val mapper: JsonMapper,
) {
    /** The number fields that are directed or bounded by a row, of every chosen option. */
    fun fields(
        definition: VariationPointDefinition,
        selected: List<SelectedOption>,
    ): List<Field> =
        selected.flatMap { choice ->
            val spec = definition.option(choice.key)?.typedValue ?: return@flatMap emptyList()
            val node = choice.valueJson?.let { parse(definition.key, it) }
            walk(choice.key, spec, node, "")
        }

    private fun walk(
        option: String,
        spec: TypedValueSpec,
        node: JsonNode?,
        path: String,
    ): List<Field> =
        when {
            spec.type == ValueType.RECORD -> {
                spec.fields.flatMap { (name, field) ->
                    walk(option, field, node?.get(name), join(path, name))
                }
            }

            spec.type.numeric &&
                (spec.direction != Direction.NONE || spec.min?.row != null || spec.max?.row != null) -> {
                val value =
                    if (node != null && node.isNumber) {
                        node.decimalValue()
                    } else if (node == null) {
                        spec.absentAs
                    } else {
                        null
                    }
                listOf(Field(option, path, spec, value))
            }

            else -> {
                emptyList()
            }
        }

    /** The directed fields of an option as path → (direction, unit); `null` when one sits in a list or map. */
    fun shape(
        spec: TypedValueSpec?,
        path: String,
    ): Map<String, Pair<Direction, String?>>? =
        when {
            spec == null -> {
                emptyMap()
            }

            spec.type == ValueType.RECORD -> {
                spec.fields.entries.fold(emptyMap()) { acc, (name, field) ->
                    acc + (shape(field, join(path, name)) ?: return null)
                }
            }

            spec.type.numeric && spec.direction != Direction.NONE -> {
                mapOf(path to (spec.direction to spec.unit))
            }

            listOfNotNull(spec.item, spec.key, spec.value).any { (shape(it, path) ?: return null).isNotEmpty() } -> {
                null
            }

            else -> {
                emptyMap()
            }
        }

    fun put(
        node: JsonNode,
        path: String,
        value: BigDecimal,
    ): JsonNode {
        if (path.isEmpty()) return mapper.getNodeFactory().numberNode(value)
        val names = path.split('.')
        var parent = node as ObjectNode
        names.dropLast(1).forEach { name ->
            parent = parent.get(name) as? ObjectNode ?: parent.putObject(name)
        }
        parent.put(names.last(), value)
        return node
    }

    fun parse(
        key: VariationPointKey,
        json: String,
    ): JsonNode =
        try {
            mapper.readTree(json)
        } catch (_: JacksonException) {
            unavailable(key, "a stored value is not JSON")
        }

    private fun join(
        path: String,
        name: String,
    ) = if (path.isEmpty()) name else "$path.$name"
}

internal fun unavailable(
    key: VariationPointKey,
    reason: String,
): Nothing = throw BoundUnavailable(key, reason)
