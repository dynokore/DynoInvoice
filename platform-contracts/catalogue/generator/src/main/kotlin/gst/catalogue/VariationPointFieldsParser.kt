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
    fun parse(
        id: String,
        cat: Map<String, Any?>,
        file: File,
    ): VpCatalogueFields {
        fun fail(msg: String): Nothing = error("gst-catalogue: $id's catalogue in $file: $msg")

        val scopeLevels =
            (cat["scope_levels"] as? List<*> ?: fail("no 'scope_levels' list")).map { level ->
                runCatching { ScopeLevel.valueOf(level.toString()) }
                    .getOrElse { fail("scope level '$level' is not one of ${ScopeLevel.entries.joinToString()} (INV-REQ-1242 b1)") }
            }
        val options =
            (cat["options"] as? List<*> ?: fail("no 'options' list")).mapIndexed { index, raw ->
                @Suppress("UNCHECKED_CAST")
                val option = raw as? Map<String, Any?> ?: fail("option #${index + 1} is not a map")
                val key = (option["key"] as? String)?.trim() ?: fail("option #${index + 1} has no 'key'")
                val strictnessText = option["strictness"] as? String ?: fail("option $key has no 'strictness'")
                val strictness =
                    runCatching { Strictness.valueOf(strictnessText) }
                        .getOrElse { fail("option $key's strictness '$strictnessText' is not one of ${Strictness.entries.joinToString()}") }
                @Suppress("UNCHECKED_CAST")
                val typed = (option["typed_value"] as? Map<String, Any?>)?.let { typedValue(it, "option $key", ::fail) }
                VpOptionSpec(key, strictness, typed)
            }
        val known =
            setOf(
                "scope_levels", "options", "must_be_elected", "multi_select", "exclusive_keys",
                "cross_scope", "cross_option", "cross_row", "note", "basis",
            )
        val unknownKeys = cat.keys - known
        if (unknownKeys.isNotEmpty()) fail("unknown field(s) ${unknownKeys.sorted().joinToString()}; extend the generator")
        return VpCatalogueFields(
            scopeLevels = scopeLevels,
            options = options,
            mustBeElected = cat["must_be_elected"] as? Boolean,
            multiSelect = cat["multi_select"] as? Boolean ?: false,
            exclusiveKeys = (cat["exclusive_keys"] as? List<*>)?.map { it.toString() } ?: emptyList(),
            crossScope = cat["cross_scope"] as? String,
            crossOption = cat["cross_option"] as? String,
            crossRow = cat["cross_row"] as? String,
            note = cat["note"] as? String,
            basis = cat["basis"] as? String,
        )
    }

    private val typedKeys =
        setOf("type", "unit", "min", "max", "default", "direction", "ref", "filter", "ordered", "requires", "fields")

    private fun typedValue(
        raw: Map<String, Any?>,
        where: String,
        fail: (String) -> Nothing,
    ): TypedValue {
        val typeText = raw["type"] as? String ?: fail("$where's typed_value has no 'type'")
        val type =
            ValueType.fromKeel(typeText)
                ?: fail("$where's typed_value type '$typeText' is not one of ${ValueType.entries.joinToString { it.keelName }}")
        val unknown = raw.keys - typedKeys
        if (unknown.isNotEmpty()) fail("$where's typed_value has unknown field(s) ${unknown.sorted().joinToString()}")
        val direction =
            (raw["direction"] as? String)?.let { d ->
                runCatching { Direction.valueOf(d) }
                    .getOrElse { fail("$where's direction '$d' is not one of ${Direction.entries.joinToString()}") }
            }
        @Suppress("UNCHECKED_CAST")
        val fields =
            (raw["fields"] as? Map<String, Any?>)?.mapValues { (name, value) ->
                val fieldMap = value as? Map<String, Any?> ?: fail("$where's field '$name' is not a map")
                typedValue(fieldMap, "$where.$name", fail)
            } ?: emptyMap()
        if (type == ValueType.RECORD && fields.isEmpty()) fail("$where is a record with no fields")
        return TypedValue(
            type = type,
            unit = raw["unit"] as? String,
            min = bound(raw["min"]),
            max = bound(raw["max"]),
            default = bound(raw["default"]),
            direction = direction,
            ref = raw["ref"] as? String,
            filter = raw["filter"] as? String,
            ordered = raw["ordered"] as? Boolean,
            requires = raw["requires"] as? String,
            fields = fields,
        )
    }

    private fun bound(value: Any?): BoundValue? =
        when (value) {
            null -> null
            is Number -> BoundValue(number = BigDecimal(value.toString()))
            else -> BoundValue(symbolic = value.toString())
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
