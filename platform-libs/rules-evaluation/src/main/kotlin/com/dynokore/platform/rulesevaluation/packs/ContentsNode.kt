package com.dynokore.platform.rulesevaluation.packs

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * `artefact.contents` that does not satisfy the schema of its pack type. The evaluation fails with it: contents are
 * never repaired and no default is applied (INV-Q-11614). [field] names the offending member by its path.
 */
class PackContentsFailure(
    val packType: String,
    val field: String,
    reason: String,
    cause: Throwable? = null,
) : IllegalStateException("$packType contents: $field $reason", cause)

/** What the decoders share: the parse, and member checks that name the path of the member that fails (API-41). */
internal class ContentsNode(
    private val packType: String,
    private val node: JsonNode,
    private val path: String,
) {
    companion object {
        private val mapper = JsonMapper()

        fun root(
            packType: String,
            contents: ByteArray,
        ): ContentsNode {
            val tree =
                try {
                    mapper.readTree(contents)
                } catch (e: tools.jackson.core.JacksonException) {
                    throw PackContentsFailure(packType, "$", "is not JSON: ${e.originalMessage}", e)
                }
            return ContentsNode(packType, tree, "$").also { it.requireObject() }
        }
    }

    fun fail(
        member: String,
        reason: String,
    ): Nothing = throw PackContentsFailure(packType, if (member.isEmpty()) path else "$path.$member", reason)

    private fun requireObject() {
        if (!node.isObject) fail("", "must be an object")
    }

    fun member(name: String): JsonNode? = node.get(name)?.takeUnless { it.isNull }

    fun has(name: String): Boolean = member(name) != null

    fun obj(name: String): ContentsNode {
        val value = member(name) ?: fail(name, "is required")
        if (!value.isObject) fail(name, "must be an object")
        return ContentsNode(packType, value, "$path.$name")
    }

    fun text(
        name: String,
        pattern: Regex? = null,
        min: Int = 0,
        max: Int = Int.MAX_VALUE,
        oneOf: Set<String>? = null,
    ): String = checkedText(name, member(name) ?: fail(name, "is required"), pattern, min, max, oneOf)

    fun optionalText(
        name: String,
        pattern: Regex? = null,
        min: Int = 0,
        max: Int = Int.MAX_VALUE,
        oneOf: Set<String>? = null,
    ): String? = member(name)?.let { checkedText(name, it, pattern, min, max, oneOf) }

    @Suppress("LongParameterList") // the one member check shared by the required and optional readers
    fun checkedText(
        name: String,
        value: JsonNode,
        pattern: Regex?,
        min: Int,
        max: Int,
        oneOf: Set<String>?,
    ): String {
        if (!value.isString) fail(name, "must be a string")
        val text = value.asString()
        return when {
            text.length < min -> fail(name, "is shorter than $min characters")
            text.length > max -> fail(name, "is longer than $max characters")
            pattern != null && !pattern.matches(text) -> fail(name, "does not match ${pattern.pattern}: '$text'")
            oneOf != null && text !in oneOf -> fail(name, "is not one of ${oneOf.sorted()}: '$text'")
            else -> text
        }
    }

    fun array(
        name: String,
        min: Int,
        max: Int,
    ): List<ContentsNode> {
        val value = member(name) ?: fail(name, "is required")
        return items(name, value, min, max)
    }

    fun items(
        name: String,
        value: JsonNode,
        min: Int,
        max: Int,
    ): List<ContentsNode> {
        if (!value.isArray) fail(name, "must be an array")
        if (value.size() < min) fail(name, "has fewer than $min items")
        if (value.size() > max) fail(name, "has more than $max items")
        return value.mapIndexed { i, item ->
            ContentsNode(packType, item, "$path.$name[$i]").also { it.requireObject() }
        }
    }
}

internal val CODE_PATTERN = Regex("^[A-Z][A-Z0-9]{0,9}(-[A-Z0-9]{1,9})?$")
internal val SOURCE_CLASSES = setOf("PRIMARY", "SECONDARY", "INFERRED")

internal fun ContentsNode.optionalBoolean(name: String): Boolean? =
    member(name)?.let { if (it.isBoolean) it.asBoolean() else fail(name, "must be a boolean") }

internal fun ContentsNode.optionalArray(
    name: String,
    min: Int,
    max: Int,
): List<ContentsNode>? = member(name)?.let { items(name, it, min, max) }

/** An array of strings, each checked like [ContentsNode.text]; the items are not objects. */
internal fun ContentsNode.texts(
    name: String,
    min: Int,
    max: Int,
    pattern: Regex,
): List<String>? {
    val value = member(name) ?: return null
    if (!value.isArray) fail(name, "must be an array")
    if (value.size() < min) fail(name, "has fewer than $min items")
    if (value.size() > max) fail(name, "has more than $max items")
    val out = value.mapIndexed { i, item -> checkedText("$name[$i]", item, pattern, 0, Int.MAX_VALUE, null) }
    if (out.toSet().size != out.size) fail(name, "has a repeated item")
    return out
}
