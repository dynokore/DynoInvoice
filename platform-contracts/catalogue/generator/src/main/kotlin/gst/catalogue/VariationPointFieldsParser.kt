package gst.catalogue

import java.io.File
import java.math.BigDecimal

/**
 * Parses one row's `catalogue` block (Keel `io_contract.catalogue`, INV-T-3000) into
 * [VpCatalogueFields]. It only type-narrows what Keel states: an unknown scope level,
 * strictness, direction or value type fails, and nothing absent is filled in.
 * Consistency rules (keys against options, strictness against class) live in
 * [VariationPointFieldsChecker], so that a parse error and a rule violation stay
 * distinguishable.
 */
object VariationPointFieldsParser {
    /** Typed access to one YAML map: a present field of the wrong type fails instead of vanishing. */
    private class Fields(
        private val map: Map<String, Any?>,
        private val where: String,
        private val fail: (String) -> Nothing,
    ) {
        fun string(name: String): String? =
            when (val v = map[name]) {
                null -> null
                is String -> v
                else -> fail("$where's '$name' must be text, got ${v::class.simpleName}")
            }

        fun boolean(name: String): Boolean? =
            when (val v = map[name]) {
                null -> null
                is Boolean -> v
                else -> fail("$where's '$name' must be true or false, got ${v::class.simpleName}")
            }

        fun int(name: String): Int? =
            when (val v = map[name]) {
                null -> null
                is Int -> v
                else -> fail("$where's '$name' must be a whole number, got ${v::class.simpleName}")
            }

        fun list(name: String): List<*>? =
            when (val v = map[name]) {
                null -> null
                is List<*> -> v
                else -> fail("$where's '$name' must be a list, got ${v::class.simpleName}")
            }

        fun stringList(name: String): List<String>? =
            list(name)?.map { it as? String ?: fail("$where's '$name' holds a non-text entry '$it'") }

        fun map(name: String): Map<String, Any?>? = map[name]?.let { asMap(it, "$where's '$name'", fail) }

        fun has(name: String) = name in map
    }

    /** Narrows a parsed YAML node to a string-keyed map, failing on anything else. */
    private fun asMap(
        raw: Any?,
        what: String,
        fail: (String) -> Nothing,
    ): Map<String, Any?> {
        if (raw !is Map<*, *>) fail("$what is not a map")
        raw.keys.forEach { if (it !is String) fail("$what has a non-text key '$it'") }
        @Suppress("UNCHECKED_CAST") // every key was just checked to be a String; values stay Any?
        return raw as Map<String, Any?>
    }

    private val optionKeys = setOf("key", "strictness", "typed_value", "acknowledged_by")

    /**
     * INV-T-3528, only so the re-vendored file loads: cross_row / cross_option are now lists of typed
     * records (Keel INV-CS-3604); 1.x keeps them as text, the records' rules joined. gst-catalogue
     * 1.9.0 parses and checks the records themselves.
     */
    private fun ruleText(
        value: Any?,
        name: String,
        fail: (String) -> Nothing,
    ): String? =
        when (value) {
            null -> null
            is String -> value
            is List<*> ->
                value.joinToString(" ") { r ->
                    (r as? Map<*, *>)?.get("rule") as? String ?: fail("$name record has no 'rule' text")
                }
            else -> fail("'$name' must be text or a list of records, got ${value::class.simpleName}")
        }

    fun parse(
        id: String,
        cat: Map<String, Any?>,
        file: File,
    ): VpCatalogueFields {
        fun fail(msg: String): Nothing = error("gst-catalogue: $id's catalogue in $file: $msg")

        val known =
            setOf(
                "scope_levels", "options", "must_be_elected", "multi_select", "exclusive_keys",
                "cross_scope", "cross_option", "cross_row", "note", "basis", "default_by_form", "installation_parameter",
                "second_approver",
                // INV-T-3528: accepted only so the re-vendored file loads; gst-catalogue 1.9.0 parses and checks the records.
                "constraints",
            )
        val unknownKeys = cat.keys - known
        if (unknownKeys.isNotEmpty()) fail("unknown field(s) ${unknownKeys.sorted().joinToString()}; extend the generator")
        val top = Fields(cat, "catalogue", ::fail)
        val scopeLevels =
            (top.list("scope_levels") ?: fail("no 'scope_levels' list")).map { level ->
                runCatching { ScopeLevel.valueOf(level.toString()) }
                    .getOrElse { fail("scope level '$level' is not one of ${ScopeLevel.entries.joinToString()} (INV-REQ-1242 b1)") }
            }
        val options =
            (top.list("options") ?: fail("no 'options' list")).mapIndexed { index, raw ->
                val option = asMap(raw, "option #${index + 1}", ::fail)
                val optionUnknown = option.keys - optionKeys
                if (optionUnknown.isNotEmpty()) {
                    fail("option #${index + 1} has unknown field(s) ${optionUnknown.sorted().joinToString()}; extend the generator")
                }
                val f = Fields(option, "option #${index + 1}", ::fail)
                val key = f.string("key")?.trim() ?: fail("option #${index + 1} has no 'key'")
                val strictnessText = f.string("strictness") ?: fail("option $key has no 'strictness'")
                val strictness =
                    runCatching { Strictness.valueOf(strictnessText) }
                        .getOrElse { fail("option $key's strictness '$strictnessText' is not one of ${Strictness.entries.joinToString()}") }
                VpOptionSpec(
                    key,
                    strictness,
                    f.map("typed_value")?.let { typedValue(it, "option $key", ::fail) },
                    f.list("acknowledged_by")?.mapIndexed { i, raw -> acknowledger(raw, "option $key's acknowledged_by #${i + 1}", ::fail) }
                        ?: emptyList(),
                )
            }
        return VpCatalogueFields(
            scopeLevels = scopeLevels,
            options = options,
            mustBeElected = top.boolean("must_be_elected"),
            multiSelect = top.boolean("multi_select") ?: false,
            exclusiveKeys = top.stringList("exclusive_keys") ?: emptyList(),
            crossScope = top.string("cross_scope"),
            crossOption = ruleText(cat["cross_option"], "cross_option", ::fail),
            crossRow = ruleText(cat["cross_row"], "cross_row", ::fail),
            note = top.string("note"),
            basis = top.string("basis"),
            defaultByForm =
                top.map("default_by_form")?.mapValues { (form, option) ->
                    option as? String ?: fail("default_by_form's '$form' must name an option key as text")
                } ?: emptyMap(),
            installationParameter =
                top.map("installation_parameter")?.takeUnless { "row" in it }?.let { typedValue(it, "installation_parameter", ::fail) },
            installationParameterRef =
                top.map("installation_parameter")?.takeIf { "row" in it }?.let { installationParameterRef(it, ::fail) },
            secondApprover = top.string("second_approver"),
        )
    }

    private val parameterRefKeys = setOf("row", "requirement", "name", "note", "rule")

    /** `installation_parameter: { row: VP-nn, requirement: INV-REQ-nnnn, ... }` (INV-Q-3202): no typed value of its own. */
    private fun installationParameterRef(
        map: Map<String, Any?>,
        fail: (String) -> Nothing,
    ): InstallationParameterRef {
        val where = "installation_parameter"
        val unknown = map.keys - parameterRefKeys
        if (unknown.isNotEmpty()) fail("$where has unknown field(s) ${unknown.sorted().joinToString()}; extend the generator")
        val f = Fields(map, where, fail)
        val row = f.string("row")?.trim() ?: fail("$where has no 'row'")
        if (!rowPattern.matches(row)) fail("$where's row '$row' is not a VP-nn id")
        return InstallationParameterRef(
            row = row,
            requirement = f.string("requirement")?.trim() ?: fail("$where names row $row but no 'requirement'"),
            name = f.string("name"),
            note = f.string("note"),
            rule = f.string("rule"),
        )
    }

    private val rowPattern = Regex("^VP-\\d+$")

    private val acknowledgerKeys = setOf("roles", "condition", "roles_by_form", "distinct_from_setter")

    private fun acknowledger(
        raw: Any?,
        where: String,
        fail: (String) -> Nothing,
    ): Acknowledger {
        val map = asMap(raw, where, fail)
        val unknown = map.keys - acknowledgerKeys
        if (unknown.isNotEmpty()) fail("$where has unknown field(s) ${unknown.sorted().joinToString()}; extend the generator")
        val f = Fields(map, where, fail)
        val conditionText = f.string("condition") ?: fail("$where has no 'condition'")
        val condition =
            runCatching { AckCondition.valueOf(conditionText) }
                .getOrElse { fail("$where's condition '$conditionText' is not one of ${AckCondition.entries.joinToString()}") }
        val byForm =
            f.map("roles_by_form")?.entries?.associate { (formText, roles) ->
                val form =
                    runCatching { DeploymentForm.valueOf(formText) }
                        .getOrElse { fail("$where's roles_by_form form '$formText' is not one of ${DeploymentForm.entries.joinToString()}") }
                if (roles !is List<*>) fail("$where's roles_by_form '$formText' must be a list of roles")
                form to roles.map { it as? String ?: fail("$where's roles_by_form '$formText' holds a non-text entry '$it'") }
            } ?: emptyMap()
        return Acknowledger(
            roles = f.stringList("roles") ?: emptyList(),
            condition = condition,
            distinctFromSetter = f.boolean("distinct_from_setter") ?: fail("$where has no 'distinct_from_setter'"),
            rolesByForm = byForm,
        )
    }

    private val typedKeys =
        setOf(
            "type", "name", "unit", "min", "max", "default", "direction", "ref", "filter", "ordered", "requires", "fields",
            "min_exclusive", "item", "key", "value", "values", "distinct", "min_items", "max_items", "length", "rule",
            "compared_with",
        )

    private fun typedValue(
        raw: Map<String, Any?>,
        where: String,
        fail: (String) -> Nothing,
    ): TypedValue {
        val f = Fields(raw, "$where's typed_value", fail)
        val typeText = f.string("type") ?: fail("$where's typed_value has no 'type'")
        val type =
            ValueType.fromKeel(typeText)
                ?: fail("$where's typed_value type '$typeText' is not one of ${ValueType.entries.joinToString { it.keelName }}")
        val unknown = raw.keys - typedKeys
        if (unknown.isNotEmpty()) fail("$where's typed_value has unknown field(s) ${unknown.sorted().joinToString()}")
        val direction =
            f.string("direction")?.let { d ->
                runCatching { Direction.valueOf(d) }
                    .getOrElse { fail("$where's direction '$d' is not one of ${Direction.entries.joinToString()}") }
            }
        val fields =
            f.map("fields")?.mapValues { (name, value) ->
                typedValue(asMap(value, "$where's field '$name'", fail), "$where.$name", fail)
            } ?: emptyMap()
        if (type == ValueType.RECORD && fields.isEmpty()) fail("$where is a record with no fields")

        fun nested(name: String): TypedValue? = f.map(name)?.let { typedValue(it, "$where.$name", fail) }
        val item = nested("item")
        val key = nested("key")
        val value = nested("value")
        val values = f.stringList("values") ?: emptyList()
        if (type == ValueType.LIST && item == null) fail("$where is a list with no item type")
        if (type == ValueType.MAP && (key == null || value == null)) fail("$where is a map without key and value types")
        if (type == ValueType.ENUM && values.isEmpty()) fail("$where is an enum with no values")
        return TypedValue(
            type = type,
            name = f.string("name"),
            unit = f.string("unit"),
            min = bound(raw["min"]),
            max = bound(raw["max"]),
            default = bound(raw["default"]),
            direction = direction,
            ref = f.string("ref"),
            filter = f.string("filter"),
            ordered = f.boolean("ordered"),
            requires = f.string("requires"),
            fields = fields,
            minExclusive = bound(raw["min_exclusive"]),
            item = item,
            key = key,
            value = value,
            values = values,
            distinct = f.boolean("distinct"),
            minItems = f.int("min_items"),
            maxItems = f.int("max_items"),
            length = bound(raw["length"]),
            rule = f.string("rule"),
            comparedWith = f.string("compared_with"),
        )
    }

    private fun bound(value: Any?): BoundValue? =
        when (value) {
            null -> null
            is Number -> BoundValue(number = BigDecimal(value.toString()))
            is String -> BoundValue(symbolic = value)
            is Map<*, *> -> {
                val row = value["row"]
                if (value.keys != setOf("row") || row !is String || !rowPattern.matches(row.trim())) {
                    error("gst-catalogue: a bound map must be exactly {row: VP-nn}, got '$value'")
                }
                BoundValue(row = row.trim())
            }
            else -> error("gst-catalogue: a bound must be a number, a symbolic name or {row: VP-nn}, got ${value::class.simpleName} '$value'")
        }
}

/** Parses pending-fields.yaml (INV-T-3000): `pending: { VP-nn: [INV-Q-nnnn, ...] }`. */
object PendingFieldsParser {
    private val questionKey = Regex("^INV-Q-\\d+$")

    fun parse(file: File): Map<String, List<String>> {
        val root = org.yaml.snakeyaml.Yaml().load<Map<String, Any?>>(file.readText()) ?: emptyMap()
        val pending = root["pending"] as? Map<*, *> ?: return emptyMap()
        return pending.entries.associate { (vp, questions) ->
            val keys = (questions as? List<*>)?.map { it.toString().trim() } ?: emptyList()
            if (keys.isEmpty() || keys.any { !questionKey.matches(it) }) {
                error("gst-catalogue: $file: the pending marker for $vp must name one or more INV-Q-nnnn question keys, got $questions")
            }
            vp.toString() to keys
        }
    }
}
