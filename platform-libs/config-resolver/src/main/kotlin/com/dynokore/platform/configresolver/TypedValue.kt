package com.dynokore.platform.configresolver

import java.math.BigDecimal

/**
 * The value type of a typed option (gst-catalogue 1.2.0 `ValueType`; ruling INV-Q-2934). Generic structure
 * only (ARC-16): what a reference or list points at is named in [TypedValueSpec.ref] and checked by the
 * owning service through [ValueConstraints] (ruling INV-Q-3167).
 */
enum class ValueType {
    INT,
    DECIMAL,
    AMOUNT,
    MONEY,
    RATE,
    DURATION,
    DATE,
    TEXT,
    ENUM,
    LIST,
    MAP,
    RECORD,
    LIST_REF,
    SET,
    REF,
    ;

    /** A number in the JSON value (a duration is a number in its unit). */
    val numeric: Boolean get() = this in NUMERIC

    private companion object {
        val NUMERIC = setOf(INT, DECIMAL, AMOUNT, MONEY, RATE, DURATION)
    }
}

/** Which way a number is stricter (gst-catalogue `Direction`). */
enum class Direction { UP_STRICTER, DOWN_STRICTER, NONE }

/**
 * A bound: a number config-resolver checks itself, a named value resolved elsewhere (e.g. "the mandatory
 * date"), which only the owning service can check (INV-Q-3167), or the value of another variation point
 * resolved at installation scope in the same owning service (e.g. VP-57's `max: {row: VP-79}`, ruling
 * INV-Q-3202), which config-resolver checks itself.
 */
data class Bound(
    val number: BigDecimal? = null,
    val named: String? = null,
    val row: VariationPointKey? = null,
) {
    init {
        require(listOfNotNull(number, named, row).size == 1) { "a bound is a number, a named value or a row" }
        require(named == null || named.isNotBlank()) { "a named bound must not be blank" }
    }

    companion object {
        fun of(number: Long): Bound = Bound(number = BigDecimal.valueOf(number))

        fun named(name: String): Bound = Bound(named = name)

        fun row(key: VariationPointKey): Bound = Bound(row = key)
    }
}

/**
 * The generic shape and bounds of a typed option's value, adapted by product-runtime from gst-catalogue's
 * `TypedValue` (INV-Q-2934, INV-Q-3167). config-resolver checks the shape, every record field present,
 * list length and items, enum membership and numeric `min`/`max`/`minExclusive`. Everything it cannot see
 * — a [Bound.named] bound, a reference ([ValueType.LIST_REF], [ValueType.REF], [ValueType.SET] items with a
 * [ref]), a worded [rule] or a [length] given as a named value — goes to the [ValueConstraints] port.
 *
 * @property requires a worded requirement such as VP-43's "justification text"; it is met by the change's
 *   mandatory reason (INV-Q-3167), so it is never sent to the port.
 * @property default the catalogue's typed default (gst-catalogue `TypedValue.default`), e.g. VP-08's window
 *   default of 4 hours (min 1, max 8, ruling INV-Q-3320): what a value stored without this number counts as.
 *   Only a number is used; a named or row default is not evaluated here.
 */
data class TypedValueSpec(
    val type: ValueType,
    val unit: String? = null,
    val min: Bound? = null,
    val max: Bound? = null,
    val minExclusive: Bound? = null,
    val direction: Direction = Direction.NONE,
    val values: List<String> = emptyList(),
    val fields: Map<String, TypedValueSpec> = emptyMap(),
    val item: TypedValueSpec? = null,
    val key: TypedValueSpec? = null,
    val value: TypedValueSpec? = null,
    val minItems: Int? = null,
    val maxItems: Int? = null,
    val length: Bound? = null,
    val distinct: Boolean = false,
    val ordered: Boolean = false,
    val ref: String? = null,
    val rule: String? = null,
    val requires: String? = null,
    val default: Bound? = null,
) {
    init {
        require(type != ValueType.ENUM || values.isNotEmpty()) { "an enum value lists its values" }
        require(type != ValueType.RECORD || fields.isNotEmpty()) { "a record names its fields" }
        require(type != ValueType.MAP || (key != null && value != null)) { "a map names its key and value" }
        require(minItems == null || minItems >= 0) { "minItems must not be negative" }
        require(maxItems == null || minItems == null || maxItems >= minItems) { "maxItems below minItems" }
        require(type.numeric || listOfNotNull(min, max).none { it.row != null }) { "only a number is bounded by a row" }
        require(listOfNotNull(minExclusive, length).none { it.row != null }) { "a row bounds only min or max" }
    }

    /** The rows bounding this value or any part of it (INV-Q-3202). */
    val boundingRows: Set<VariationPointKey>
        get() =
            listOfNotNull(min?.row, max?.row).toSet() +
                listOfNotNull(item, key, value).flatMap { it.boundingRows } +
                fields.values.flatMap { it.boundingRows }

    /** The number this value is fixed at (`min` = `max`), e.g. VP-79's DAYS_14 (INV-Q-3223). */
    val fixed: BigDecimal?
        get() {
            val low = min?.number ?: return null
            return low.takeIf { max?.number?.compareTo(it) == 0 }
        }

    /**
     * The number a value stored without it counts as: the catalogue [default], else the number it is [fixed]
     * at; `null` when neither is a number. VP-08's DEFAULT_PAIR_4H window counts as its default 4 hours
     * (INV-Q-3320, superseding INV-Q-3204's fixed 4 hours).
     */
    val absentAs: BigDecimal?
        get() = default?.number ?: fixed

    /** Whether any part of this spec needs the owning service's [ValueConstraints] (INV-Q-3167). */
    val needsOwningService: Boolean
        get() =
            listOfNotNull(min, max, minExclusive, length).any { it.named != null } ||
                rule != null ||
                type in REFERENCES ||
                (type == ValueType.SET && ref != null) ||
                listOfNotNull(item, key, value).any { it.needsOwningService } ||
                fields.values.any { it.needsOwningService }

    private companion object {
        val REFERENCES = setOf(ValueType.LIST_REF, ValueType.REF)
    }
}
