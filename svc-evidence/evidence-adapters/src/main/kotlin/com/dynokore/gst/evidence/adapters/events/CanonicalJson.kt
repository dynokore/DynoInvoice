package com.dynokore.gst.evidence.adapters.events

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * Canonical JSON: object keys sorted (by UTF-16 code unit, at every depth), no insignificant whitespace, array order
 * kept. The content hash of a record is therefore a function of the event alone, not of the key order its producer
 * happened to write.
 */
internal object CanonicalJson {
    private val mapper = JsonMapper.builder().build()

    fun bytes(node: JsonNode): ByteArray = mapper.writeValueAsString(sorted(node)).toByteArray(Charsets.UTF_8)

    private fun sorted(node: JsonNode): JsonNode =
        when {
            node.isObject -> {
                val out: ObjectNode = JsonNodeFactory.instance.objectNode()
                node
                    .propertyNames()
                    .toList()
                    .sorted()
                    .forEach { out.set(it, sorted(node.get(it))) }
                out
            }

            node.isArray -> {
                val out: ArrayNode = JsonNodeFactory.instance.arrayNode()
                node.forEach { out.add(sorted(it)) }
                out
            }

            else -> {
                node
            }
        }
}
