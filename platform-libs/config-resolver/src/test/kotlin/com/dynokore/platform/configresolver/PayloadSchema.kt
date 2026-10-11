package com.dynokore.platform.configresolver

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Validates a payload against a pinned platform-contracts JSON Schema (2020-12) under
 * `src/test/resources/contracts` (README there). It implements the keywords the pinned schema uses (type,
 * properties, required, enum, additionalProperties, format date) and fails on any other keyword it meets, so a
 * contract that grows a keyword cannot pass unchecked.
 */
object PayloadSchema {
    private val mapper = ObjectMapper()
    private val annotations = setOf("\$schema", "\$id", "title", "description")
    private val objectKeywords = setOf("required", "properties", "additionalProperties")

    /** Errors of [instance] against [schemaPath] (relative to `contracts/`); empty when it conforms. */
    fun errors(
        schemaPath: String,
        instance: JsonNode,
    ): List<String> {
        val resource =
            checkNotNull(javaClass.getResource("/contracts/$schemaPath")) { "no pinned contract $schemaPath" }
        val text = resource.readText()
        return buildList { validate(mapper.readTree(text), instance, "$", this) }
    }

    private fun validate(
        schema: JsonNode,
        node: JsonNode,
        at: String,
        errors: MutableList<String>,
    ) {
        schema.properties().forEach { (keyword, value) ->
            when (keyword) {
                in annotations -> Unit
                "type" -> if (!hasType(node, value.asString())) errors += "$at: not a ${value.asString()}"
                "enum" -> if (value.none { it == node }) errors += "$at: $node not in $value"
                "format" -> checkFormat(value.asString(), node, at, errors)
                in objectKeywords -> if (node.isObject) checkObject(keyword, schema, node, at, errors)
                else -> error("unsupported keyword $keyword at $at")
            }
        }
    }

    private fun checkObject(
        keyword: String,
        schema: JsonNode,
        node: JsonNode,
        at: String,
        errors: MutableList<String>,
    ) {
        val value = schema.get(keyword)
        when (keyword) {
            "required" -> value.forEach { if (!node.has(it.asString())) errors += "$at: missing ${it.asString()}" }

            "properties" ->
                value.properties().forEach { (name, sub) ->
                    node.get(name)?.let { validate(sub, it, "$at.$name", errors) }
                }

            else ->
                if (!value.asBoolean()) {
                    val known = schema.get("properties")?.propertyNames() ?: emptySet()
                    node.propertyNames().filterNot { it in known }.forEach { errors += "$at: unexpected $it" }
                }
        }
    }

    private fun hasType(
        node: JsonNode,
        type: String,
    ) = when (type) {
        "object" -> node.isObject
        "string" -> node.isString
        else -> error("unsupported type $type")
    }

    private fun checkFormat(
        format: String,
        node: JsonNode,
        at: String,
        errors: MutableList<String>,
    ) {
        check(format == "date") { "unsupported format $format" }
        if (!node.isString) return
        try {
            LocalDate.parse(node.asString())
        } catch (e: DateTimeParseException) {
            errors += "$at: not a date (${e.message})"
        }
    }
}
