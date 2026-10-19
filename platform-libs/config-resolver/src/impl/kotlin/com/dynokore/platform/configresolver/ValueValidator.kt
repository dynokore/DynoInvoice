package com.dynokore.platform.configresolver

import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Checks a selection against its catalogue row (INV-REQ-1239 behaviour #3, ruling INV-Q-3167): the options
 * exist and are offered, the selection's shape, and each typed value's shape, record completeness, list
 * length, enum membership and numeric bounds. What only the owning service can see is reported through
 * [needsOwningService], never guessed.
 */
internal class ValueValidator(
    private val form: DeploymentForm,
) {
    private val mapper: JsonMapper =
        JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()

    /**
     * @return whether the owning service's [ValueConstraints] must also check the selection.
     * @throws PolicyRefused when the selection is refused.
     */
    fun check(
        definition: VariationPointDefinition,
        selected: List<SelectedOption>,
    ): Boolean {
        val key = definition.key
        if (selected.isEmpty()) refuse(key, PolicyRefusal.SELECTION_INVALID, "no option selected")
        val keys = selected.map { it.key }
        if (keys.toSet().size != keys.size) refuse(key, PolicyRefusal.SELECTION_INVALID, "an option is selected twice")
        if (!definition.multiSelect && keys.size > 1) {
            refuse(key, PolicyRefusal.SELECTION_INVALID, "the row takes exactly one option")
        }
        if (keys.size > 1 && keys.any { it in definition.exclusiveKeys }) {
            refuse(key, PolicyRefusal.SELECTION_INVALID, "an exclusive option must be the only one")
        }
        val offered = definition.offeredOptions(form)
        var needsService = definition.crossOption != null
        selected.forEach { choice ->
            val option =
                definition.option(choice.key)
                    ?: refuse(key, PolicyRefusal.UNKNOWN_OPTION, "no option '${choice.key}'")
            if (choice.key !in offered) {
                val refusal =
                    if (option.strictness ==
                        Strictness.LOOSER
                    ) {
                        PolicyRefusal.LOOSER_NOT_OFFERED
                    } else {
                        PolicyRefusal.UNKNOWN_OPTION
                    }
                refuse(key, refusal, "option '${choice.key}' is not offered")
            }
            needsService = checkValue(key, option, choice) || needsService
        }
        return needsService
    }

    private fun checkValue(
        key: VariationPointKey,
        option: OptionSpec,
        choice: SelectedOption,
    ): Boolean {
        if (!option.typed) {
            if (choice.valueJson !=
                null
            ) {
                refuse(key, PolicyRefusal.OUT_OF_BOUNDS, "option '${option.key}' takes no value")
            }
            return false
        }
        val json = choice.valueJson ?: refuse(key, PolicyRefusal.OUT_OF_BOUNDS, "option '${option.key}' needs a value")
        val spec =
            option.typedValue
                ?: refuse(key, PolicyRefusal.OUT_OF_BOUNDS, "option '${option.key}' has no value spec to check against")
        val node =
            try {
                mapper.readTree(json)
            } catch (_: JacksonException) {
                refuse(key, PolicyRefusal.OUT_OF_BOUNDS, "option '${option.key}' value is not JSON")
            }
        val problem = problem(spec, node, option.key)
        if (problem != null) refuse(key, PolicyRefusal.OUT_OF_BOUNDS, problem)
        return spec.needsOwningService
    }

    /** A numeric bound on a type that can take none (a counted type takes one), or `null`. */
    private fun boundProblem(
        spec: TypedValueSpec,
        path: String,
    ): String? {
        val bounded =
            !spec.type.numeric && listOfNotNull(spec.min, spec.max, spec.minExclusive).any { it.number != null }
        return if (bounded && spec.type !in COUNTED) "$path has a numeric bound its type cannot take" else null
    }

    /** The first reason [node] does not fit [spec], or `null`. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // one branch per catalogue value type, first problem wins
    private fun problem(
        spec: TypedValueSpec,
        node: JsonNode,
        path: String,
    ): String? {
        if (node.isNull) return "$path is null"
        boundProblem(spec, path)?.let { return it }
        return when (spec.type) {
            ValueType.INT -> {
                if (!node.isIntegralNumber) "$path must be a whole number" else numberProblem(spec, node, path)
            }

            ValueType.DECIMAL, ValueType.AMOUNT, ValueType.MONEY, ValueType.RATE, ValueType.DURATION -> {
                if (!node.isNumber) "$path must be a number" else numberProblem(spec, node, path)
            }

            ValueType.DATE -> {
                if (!node.isString || !isDate(node.stringValue())) "$path must be an ISO date" else null
            }

            ValueType.TEXT -> {
                if (!node.isString || node.stringValue().isBlank()) "$path must be non-blank text" else null
            }

            ValueType.ENUM -> {
                enumProblem(spec, node, path)
            }

            ValueType.REF -> {
                if (!isReference(node)) "$path must be a reference" else null
            }

            ValueType.LIST_REF -> {
                if (isReferenceOrList(node)) {
                    countProblem(spec, if (node.isArray) node.size() else 1, path)
                } else {
                    "$path must be a reference or a list of references"
                }
            }

            ValueType.LIST, ValueType.SET -> {
                collectionProblem(spec, node, path)
            }

            ValueType.MAP -> {
                mapProblem(spec, node, path)
            }

            ValueType.RECORD -> {
                recordProblem(spec, node, path)
            }
        }
    }

    private fun collectionProblem(
        spec: TypedValueSpec,
        node: JsonNode,
        path: String,
    ): String? {
        if (!node.isArray) return "$path must be a list"
        val items = node.toList()
        return countProblem(spec, items.size, path)
            ?: duplicateProblem(spec, items, path)
            ?: itemProblem(spec, items, path)
    }

    private fun itemProblem(
        spec: TypedValueSpec,
        items: List<JsonNode>,
        path: String,
    ): String? {
        val item = spec.item
        return when {
            item != null -> {
                items.withIndex().firstNotNullOfOrNull { (i, n) -> problem(item, n, "$path[$i]") }
            }

            spec.ref != null -> {
                items.withIndex().firstNotNullOfOrNull { (i, n) ->
                    if (isReference(n)) null else "$path[$i] must be a reference"
                }
            }

            else -> {
                "$path has no item spec to check against"
            }
        }
    }

    private fun mapProblem(
        spec: TypedValueSpec,
        node: JsonNode,
        path: String,
    ): String? {
        if (!node.isObject) return "$path must be an object"
        return countProblem(spec, node.size(), path) ?: entriesProblem(spec, node, path)
    }

    private fun entriesProblem(
        spec: TypedValueSpec,
        node: JsonNode,
        path: String,
    ): String? {
        val keySpec = checkNotNull(spec.key)
        val valueSpec = checkNotNull(spec.value)
        return node.properties().firstNotNullOfOrNull { (k, v) ->
            problem(keySpec, mapper.stringNode(k), "$path key '$k'") ?: problem(valueSpec, v, "$path['$k']")
        }
    }

    /** Every field present and no other (ruling INV-Q-3167: a partial record has no defined meaning). */
    private fun recordProblem(
        spec: TypedValueSpec,
        node: JsonNode,
        path: String,
    ): String? {
        if (!node.isObject) return "$path must be an object"
        val names = node.propertyNames().toSet()
        val missing = spec.fields.keys - names
        val extra = names - spec.fields.keys
        return missing.takeIf { it.isNotEmpty() }?.let { "$path misses ${it.sorted()}" }
            ?: extra.takeIf { it.isNotEmpty() }?.let { "$path has unknown ${it.sorted()}" }
            ?: spec.fields.entries.firstNotNullOfOrNull { (name, field) ->
                problem(field, node.get(name), "$path.$name")
            }
    }

    private companion object {
        /** Types whose numeric min/max bound the number of items. */
        val COUNTED = setOf(ValueType.LIST, ValueType.SET, ValueType.MAP, ValueType.LIST_REF)
    }
}

/** [node] is not one of [spec]'s values, or `null`. */
private fun enumProblem(
    spec: TypedValueSpec,
    node: JsonNode,
    path: String,
): String? = if (!node.isString || node.stringValue() !in spec.values) "$path must be one of ${spec.values}" else null

internal fun refuse(
    key: VariationPointKey,
    refusal: PolicyRefusal,
    detail: String,
): Nothing = throw PolicyRefused(refusal, key, detail)

private fun numberProblem(
    spec: TypedValueSpec,
    node: JsonNode,
    path: String,
): String? {
    val value = node.decimalValue()
    return spec.min
        ?.number
        ?.takeIf { value < it }
        ?.let { "$path is below ${it.toPlainString()}" }
        ?: spec.max
            ?.number
            ?.takeIf { value > it }
            ?.let { "$path is above ${it.toPlainString()}" }
        ?: spec.minExclusive
            ?.number
            ?.takeIf { value <= it }
            ?.let { "$path must be above ${it.toPlainString()}" }
}

private fun duplicateProblem(
    spec: TypedValueSpec,
    items: List<JsonNode>,
    path: String,
): String? =
    if ((spec.type == ValueType.SET || spec.distinct) && items.toSet().size != items.size) {
        "$path holds an item twice"
    } else {
        null
    }

private fun countProblem(
    spec: TypedValueSpec,
    count: Int,
    path: String,
): String? {
    val size = BigDecimal.valueOf(count.toLong())
    return spec.minItems?.takeIf { count < it }?.let { "$path has fewer than $it items" }
        ?: spec.maxItems?.takeIf { count > it }?.let { "$path has more than $it items" }
        ?: spec.length
            ?.number
            ?.takeIf { size.compareTo(it) != 0 }
            ?.let { "$path must hold $it items" }
        ?: spec.min
            ?.number
            ?.takeIf { size < it }
            ?.let { "$path has fewer than $it items" }
        ?: spec.max
            ?.number
            ?.takeIf { size > it }
            ?.let { "$path has more than $it items" }
}

private fun isReferenceOrList(node: JsonNode) =
    isReference(node) || (node.isArray && node.size() > 0 && node.all { isReference(it) })

private fun isReference(node: JsonNode) = node.isString && node.stringValue().isNotBlank()

private fun isDate(text: String) =
    try {
        LocalDate.parse(text)
        true
    } catch (_: DateTimeParseException) {
        false
    }
