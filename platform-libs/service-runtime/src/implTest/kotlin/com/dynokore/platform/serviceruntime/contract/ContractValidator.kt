package com.dynokore.platform.serviceruntime.contract

import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Validates requests, responses and payloads against the pinned platform-contracts files under
 * `src/implTest/resources/contracts` (README there): the OpenAPI 3.1 operations of `service-runtime/openapi.yaml`
 * and the JSON Schemas (2020-12) they and the platform events reference. It implements the keywords those files
 * use (type, required, properties, additionalProperties, enum, const, pattern, minLength, maxLength, minimum,
 * format date-time, date, uuid and uri, items, $ref within a file and to a sibling file) and fails on any other
 * keyword it meets, so a contract that grows a keyword cannot pass unchecked.
 */
class ContractValidator {
    private val mapper = ObjectMapper()
    private val documents = HashMap<String, JsonNode>()

    private fun document(path: String): JsonNode =
        documents.getOrPut(path) {
            val text = checkNotNull(javaClass.getResource("/contracts/$path")) { "no pinned contract $path" }.readText()
            if (path.endsWith(".yaml")) mapper.valueToTree(Yaml().load<Any>(text)) else mapper.readTree(text)
        }

    /** Errors of [instance] against the JSON Schema file [schemaPath] (relative to `contracts/`). */
    fun schemaErrors(
        schemaPath: String,
        instance: JsonNode,
    ): List<String> = buildList { validate(Location(schemaPath, document(schemaPath)), instance, "$", this) }

    /** The operation of [path] (an OpenAPI path template's concrete form) and [method] in [openapiPath]. */
    fun operation(
        openapiPath: String,
        method: String,
        path: String,
    ): Operation {
        val api = document(openapiPath)
        val (template, item) =
            api.get("paths").properties().map { it.key to it.value }.firstOrNull { (template, _) ->
                Regex(
                    "^" + template.split(Regex("\\{[^}]+}")).joinToString("[^/]+") { Regex.escape(it) } + "$",
                ).matches(path)
            } ?: error("no path in $openapiPath matches $path")
        val operation = item.get(method.lowercase()) ?: error("$template has no $method")
        return Operation(openapiPath, operation)
    }

    inner class Operation(
        private val file: String,
        val node: JsonNode,
    ) {
        val operationId: String get() = node.get("operationId").asString()

        /** The `x-operation.reversibility` the contract declares (INV-REQ-957 b6). */
        val reversibility: String get() = node.get("x-operation").get("reversibility").asString()

        /** Errors of a request with these [headers] (lower-case names) and [body]. */
        fun requestErrors(
            headers: Map<String, String>,
            body: JsonNode,
        ): List<String> =
            buildList {
                node.get("parameters")?.forEach { reference ->
                    val parameter = resolve(Location(file, reference)).node
                    if (parameter.get("in").asString() == "header" && parameter.get("required")?.asBoolean() == true) {
                        val name = parameter.get("name").asString()
                        val value = headers[name.lowercase()]
                        if (value == null) {
                            add("missing required header $name")
                        } else {
                            validate(
                                resolve(Location(file, parameter.get("schema"))),
                                mapper.valueToTree(value),
                                name,
                                this,
                            )
                        }
                    }
                }
                val schema =
                    node
                        .get("requestBody")
                        .get("content")
                        .get("application/json")
                        .get("schema")
                validate(Location(file, schema), body, "$", this)
            }

        /** Errors of a response with [status], [contentType] and [body]. */
        fun responseErrors(
            status: Int,
            contentType: String,
            body: JsonNode,
        ): List<String> {
            val responses = node.get("responses")
            val declared =
                (responses.get(status.toString()) ?: responses.get("default"))
                    ?: return listOf("status $status is not declared for $operationId")
            val response = resolve(Location(file, declared))
            val media = response.node.get("content")?.get(contentType.substringBefore(';').trim())
            return if (media == null) {
                listOf("$operationId $status declares no $contentType")
            } else {
                buildList { validate(Location(response.file, media.get("schema")), body, "$", this) }
            }
        }
    }

    private data class Location(
        val file: String,
        val node: JsonNode,
    )

    private fun resolve(location: Location): Location {
        var current = location
        while (current.node.has("\$ref")) {
            val ref = current.node.get("\$ref").asString()
            val (target, pointer) = ref.split('#', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val file = if (target.isEmpty()) current.file else URI(current.file).resolve(target).toString()
            val root = document(file)
            current =
                Location(
                    file,
                    if (pointer.isEmpty()) {
                        root
                    } else {
                        root.at(pointer).also {
                            check(!it.isMissingNode) { "unresolved $ref" }
                        }
                    },
                )
        }
        return current
    }

    private fun validate(
        at: Location,
        instance: JsonNode,
        path: String,
        errors: MutableList<String>,
    ) {
        val resolved = resolve(at)
        val schema = resolved.node
        val unknown = schema.propertyNames().filterNot { it in KNOWN_KEYWORDS || it.startsWith("x-") }
        check(unknown.isEmpty()) { "validator does not implement $unknown at $path" }
        if (!typeErrors(schema, instance, path, errors)) return
        valueErrors(schema, instance, path, errors)
        combinatorErrors(resolved, instance, path, errors)
        if (instance.isString) stringErrors(schema, instance.asString(), path, errors)
        if (instance.isNumber) {
            schema.get("minimum")?.let { if (instance.asLong() < it.asLong()) errors += "$path: below ${it.asLong()}" }
        }
        if (instance.isArray) arrayErrors(resolved, instance, path, errors)
        if (instance.isObject) objectErrors(resolved, instance, path, errors)
    }

    /** False when the declared type fails, which ends the checks of this schema. */
    private fun typeErrors(
        schema: JsonNode,
        instance: JsonNode,
        path: String,
        errors: MutableList<String>,
    ): Boolean {
        val declared = schema.get("type") ?: return true
        val types = if (declared.isArray) declared.toList().map { it.asString() } else listOf(declared.asString())
        val holds = types.any { typeHolds(it, instance) }
        if (!holds) errors += "$path: expected ${types.joinToString("|")}, was ${instance.nodeType}"
        return holds
    }

    private fun valueErrors(
        schema: JsonNode,
        instance: JsonNode,
        path: String,
        errors: MutableList<String>,
    ) {
        schema.get("const")?.let { if (it != instance) errors += "$path: expected const $it" }
        schema.get("enum")?.let { values ->
            if (values.none { it == instance }) errors += "$path: $instance not in $values"
        }
    }

    private fun combinatorErrors(
        resolved: Location,
        instance: JsonNode,
        path: String,
        errors: MutableList<String>,
    ) {
        val schema = resolved.node
        schema.get("allOf")?.forEach { validate(Location(resolved.file, it), instance, path, errors) }
        schema.get("not")?.let { negated ->
            val found = mutableListOf<String>()
            validate(Location(resolved.file, negated), instance, path, found)
            if (found.isEmpty()) errors += "$path: matches a schema it must not match"
        }
        schema.get("if")?.let { condition ->
            val found = mutableListOf<String>()
            validate(Location(resolved.file, condition), instance, path, found)
            val branch = if (found.isEmpty()) schema.get("then") else schema.get("else")
            branch?.let { validate(Location(resolved.file, it), instance, path, errors) }
        }
    }

    private fun stringErrors(
        schema: JsonNode,
        text: String,
        path: String,
        errors: MutableList<String>,
    ) {
        schema.get("pattern")?.let {
            if (!Regex(it.asString()).containsMatchIn(text)) errors += "$path: '$text' does not match ${it.asString()}"
        }
        schema.get("minLength")?.let { if (text.length < it.asInt()) errors += "$path: shorter than ${it.asInt()}" }
        schema.get("maxLength")?.let { if (text.length > it.asInt()) errors += "$path: longer than ${it.asInt()}" }
        schema.get("format")?.asString()?.let { format ->
            if (!formatHolds(format, text)) errors += "$path: '$text' is not a $format"
        }
    }

    private fun arrayErrors(
        resolved: Location,
        instance: JsonNode,
        path: String,
        errors: MutableList<String>,
    ) {
        val schema = resolved.node
        schema.get("minItems")?.let {
            if (instance.size() < it.asInt()) errors += "$path: fewer than ${it.asInt()} items"
        }
        schema.get("items")?.let { items ->
            instance.forEachIndexed { i, item -> validate(Location(resolved.file, items), item, "$path[$i]", errors) }
        }
    }

    private fun objectErrors(
        resolved: Location,
        instance: JsonNode,
        path: String,
        errors: MutableList<String>,
    ) {
        val schema = resolved.node
        schema.get("required")?.forEach {
            if (!instance.has(it.asString())) errors += "$path: missing required ${it.asString()}"
        }
        val properties = schema.get("properties")
        instance.properties().forEach { (name, value) ->
            val property = properties?.get(name)
            when {
                property != null -> {
                    validate(Location(resolved.file, property), value, "$path.$name", errors)
                }

                schema.get("additionalProperties")?.isBoolean == true &&
                    !schema.get("additionalProperties").asBoolean() -> {
                    errors += "$path: unexpected property $name"
                }
            }
        }
    }

    private fun typeHolds(
        type: String,
        instance: JsonNode,
    ): Boolean =
        when (type) {
            "object" -> instance.isObject
            "array" -> instance.isArray
            "string" -> instance.isString
            "integer" -> instance.isIntegralNumber
            "number" -> instance.isNumber
            "boolean" -> instance.isBoolean
            "null" -> instance.isNull
            else -> error("type $type")
        }

    private fun formatHolds(
        format: String,
        text: String,
    ): Boolean =
        runCatching {
            when (format) {
                "date-time" -> Instant.parse(text)
                "date" -> LocalDate.parse(text)
                "uuid" -> check(UUID.fromString(text).toString() == text.lowercase())
                "uri" -> check(URI(text).isAbsolute)
                else -> error("format $format")
            }
        }.isSuccess

    companion object {
        private val KNOWN_KEYWORDS =
            setOf(
                "\$schema",
                "\$id",
                "\$defs",
                "title",
                "description",
                "type",
                "required",
                "properties",
                "additionalProperties",
                "enum",
                "const",
                "pattern",
                "minLength",
                "maxLength",
                "minimum",
                "format",
                "items",
                "examples",
                "default",
                "minItems",
                "allOf",
                "not",
                "if",
                "then",
                "else",
            )
    }
}
