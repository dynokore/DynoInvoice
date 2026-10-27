package com.dynokore.gst.notification.syncedge

/** A minimal value that satisfies a schema of [document]: required properties, first enum value, lower bounds. */
class SampleValues(
    private val document: Map<String, Any?>,
) {
    fun resolve(node: Map<*, *>): Map<*, *> = (node["\$ref"] as? String)?.let { reference(it) as Map<*, *> } ?: node

    private fun reference(ref: String): Any? =
        ref.removePrefix("#/").split('/').fold(document as Any?) { at, key -> (at as Map<*, *>)[key] }

    fun sample(schema: Any?): Any? {
        val node = resolve(schema as Map<*, *>)
        return fixed(node) ?: composed(node) ?: typed(node)
    }

    private fun fixed(node: Map<*, *>): Any? = (node["enum"] as? List<*>)?.first() ?: node["const"]

    private fun composed(node: Map<*, *>): Any? {
        val all = node["allOf"] as? List<*>
        val one = (node["oneOf"] ?: node["anyOf"]) as? List<*>
        return when {
            all != null -> {
                all.map { sample(it) }.filterIsInstance<Map<*, *>>().fold(mapOf<Any?, Any?>()) { a, b ->
                    a +
                        b
                }
            }

            one != null -> {
                sample(one.first())
            }

            else -> {
                null
            }
        }
    }

    private fun typed(node: Map<*, *>): Any? =
        when (node["type"]) {
            "object" -> {
                obj(node)
            }

            "array" -> {
                if (((node["minItems"] as? Number)?.toInt() ?: 0) >
                    0
                ) {
                    listOf(sample(node["items"]))
                } else {
                    emptyList()
                }
            }

            "integer" -> {
                (node["minimum"] as? Number)?.toLong() ?: 1L
            }

            "number" -> {
                (node["minimum"] as? Number)?.toDouble() ?: 1.0
            }

            "boolean" -> {
                true
            }

            else -> {
                string(node)
            }
        }

    private fun obj(node: Map<*, *>): Map<Any?, Any?> {
        val required = (node["required"] as? List<*>).orEmpty()
        val properties = (node["properties"] as? Map<*, *>).orEmpty()
        return properties.filterKeys { it in required }.mapValues { sample(it.value) }
    }

    private fun string(node: Map<*, *>): String =
        when (node["format"]) {
            "uuid" -> UUID_SAMPLE
            "date-time" -> "2026-10-01T00:00:00Z"
            "date" -> "2026-10-01"
            else -> "a".repeat(maxOf(1, (node["minLength"] as? Number)?.toInt() ?: 1))
        }

    fun text(value: Any?): String = if (value is String) value else json(value)

    fun json(value: Any?): String =
        when (value) {
            null -> "null"
            is Map<*, *> -> value.entries.joinToString(",", "{", "}") { "\"${it.key}\":${json(it.value)}" }
            is List<*> -> value.joinToString(",", "[", "]") { json(it) }
            is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            else -> value.toString()
        }

    private companion object {
        const val UUID_SAMPLE = "5b1f0c1e-7a37-4c45-9a52-2f0a4f0b9b11"
    }
}
