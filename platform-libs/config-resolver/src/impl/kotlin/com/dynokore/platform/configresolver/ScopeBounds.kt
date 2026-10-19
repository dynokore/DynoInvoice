package com.dynokore.platform.configresolver

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate

/**
 * VP-08's cross-scope rule and the VP-57/VP-67 installation bound (INV-T-3176), as generic structure only
 * (ARC-16):
 *  - **Cross-scope** ([VariationPointDefinition.crossScope]): a narrower value's directed numeric fields
 *    (direction UP/DOWN_STRICTER) may not be looser than the same-named fields of the value the nearest
 *    broader allowed scope resolves to; fields with no direction (VP-08's pair) are never compared
 *    (INV-REQ-780 b1, INV-Q-2936, INV-Q-3204). An option without a value counts by its catalogue default,
 *    else its fixed number ([TypedValueSpec.absentAs]): VP-08's DEFAULT_PAIR_4H window, configurable from
 *    1 to 8 hours, counts as its default 4 hours (INV-Q-3320).
 *  - **Installation bound** ([Bound.row]): a number bounded by another row takes that row's value resolved
 *    at installation scope in the same service: a single number in the same unit, else the bound cannot be
 *    evaluated (INV-Q-3202).
 *
 * Writes are refused beyond a limit; resolution caps the stored value at it and says so (INV-REQ-1242
 * behaviour #5, INV-Q-3206). A limit that cannot be evaluated throws [BoundUnavailable] (fail closed).
 *
 * @param resolve the full resolution (itself capped), used for the broader scope and the bounding row.
 */
internal class ScopeBounds(
    private val catalogue: VariationPointCatalogue,
    private val resolve: (VariationPointKey, PolicyScope, LocalDate) -> ResolvedSetting,
) {
    private val mapper: JsonMapper =
        JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
    private val walker = BoundFields(mapper)

    /** Why config-resolver cannot evaluate the row's cross-scope rule or installation bound, or `null`. */
    fun unsupported(definition: VariationPointDefinition): String? =
        when {
            definition.installationBound && definition.installationParameter == null -> {
                "the row declares an installation bound but names no installation parameter row"
            }

            definition.crossScope == null -> {
                null
            }

            else -> {
                crossScopeUnsupported(definition)
            }
        }

    private fun crossScopeUnsupported(definition: VariationPointDefinition): String? {
        val shapes =
            definition.options.map { option ->
                walker.shape(option.typedValue, "")
                    ?: return "a directed field sits in a list or map"
            }
        return when {
            shapes.distinct().size != 1 -> "the options do not all carry the same directed fields"
            shapes.first().isEmpty() -> "the row has no directed field to compare"
            else -> null
        }
    }

    /** Whether the row has anything this class applies. */
    fun applies(definition: VariationPointDefinition): Boolean =
        definition.crossScope != null || definition.installationParameter != null

    /**
     * The limits on [selected], set at [level] of [scope], in force at [at].
     *
     * @throws BoundUnavailable when a limit cannot be evaluated.
     */
    fun limits(
        definition: VariationPointDefinition,
        scope: PolicyScope,
        level: ScopeLevel,
        selected: List<SelectedOption>,
        at: LocalDate,
    ): List<Limit> {
        val fields = walker.fields(definition, selected)
        return crossScopeLimits(definition, scope, level, fields, at) + rowLimits(definition, fields, at)
    }

    /** The broader scope levels in [scope] whose values limit a value set at [level]. */
    fun broaderLevels(
        definition: VariationPointDefinition,
        scope: PolicyScope,
        level: ScopeLevel,
    ): List<ScopeLevel> =
        if (definition.crossScope == null) {
            emptyList()
        } else {
            definition.scopeLevels.filter { it < level && scope.ref(it) != null }
        }

    /**
     * [resolved] with its directed fields capped at [limits] (INV-REQ-1242 behaviour #5). The stored value
     * is not touched; the caps are listed on the result.
     */
    fun cap(
        resolved: ResolvedSetting,
        limits: List<Limit>,
    ): ResolvedSetting {
        val caps = mutableListOf<PolicyCap>()
        val selected =
            resolved.selected.map { option ->
                val mine = limits.filter { it.field.option == option.key && it.violatedBy(it.field.value) }
                if (mine.isEmpty()) return@map option
                // An option stored without a value counts by its default (INV-Q-3320); its capped value is
                // built from the spec, the key kept (INV-Q-3206).
                var node: JsonNode = option.valueJson?.let { mapper.readTree(it) } ?: mapper.createObjectNode()
                mine.groupBy { it.field.path }.forEach { (path, onField) ->
                    val stored = checkNotNull(onField.first().field.value)
                    var value = stored
                    // The tightest ceiling, then the tightest floor; each applied limit is listed.
                    listOf(true, false).forEach { ceiling ->
                        onField
                            .filter { it.ceiling == ceiling }
                            .minWithOrNull {
                                a,
                                b,
                                ->
                                if (ceiling) a.bound.compareTo(b.bound) else b.bound.compareTo(a.bound)
                            }?.takeIf { it.violatedBy(value) }
                            ?.let {
                                value = it.bound
                                caps += PolicyCap(option.key, path, stored, it.bound, it.by, it.level)
                            }
                    }
                    node = walker.put(node, path, value)
                }
                SelectedOption(option.key, mapper.writeValueAsString(node))
            }
        return if (caps.isEmpty()) resolved else resolved.copy(selected = selected, caps = resolved.caps + caps)
    }

    /** A number field of a chosen option: [value] from the stored value, else [TypedValueSpec.absentAs]. */
    data class Field(
        val option: String,
        val path: String,
        val spec: TypedValueSpec,
        val value: BigDecimal?,
    )

    /**
     * A limit on one field: the field may not go above ([ceiling]) or below [bound], set by [by] at [level].
     *
     * @property crossScope the limit comes from a broader scope's value, not an installation bound.
     */
    data class Limit(
        val field: Field,
        val bound: BigDecimal,
        val ceiling: Boolean,
        val by: VariationPointKey,
        val level: ScopeLevel,
        val crossScope: Boolean,
    ) {
        fun violatedBy(value: BigDecimal?): Boolean = value != null && if (ceiling) value > bound else value < bound
    }

    private fun crossScopeLimits(
        definition: VariationPointDefinition,
        scope: PolicyScope,
        level: ScopeLevel,
        fields: List<Field>,
        at: LocalDate,
    ): List<Limit> {
        val broader = broaderLevels(definition, scope, level).maxOrNull()
        val directed = fields.filter { it.spec.direction != Direction.NONE }
        if (broader == null || directed.isEmpty()) return emptyList()
        val key = definition.key
        val wider = resolving(key) { resolve(key, scope.narrowedTo(broader), at) }
        val widerFields =
            walker
                .fields(
                    definition,
                    wider.selected,
                ).filter { it.spec.direction != Direction.NONE }
                .groupBy { it.path }
        return directed.flatMap { field ->
            val same =
                widerFields[field.path] ?: unavailable(key, "the $broader value has no '${field.path}'")
            if (field.value == null) unavailable(key, "option '${field.option}' has no '${field.path}'")
            same.map { other ->
                val bound = other.value ?: unavailable(key, "the $broader value has no '${field.path}'")
                if (other.spec.direction != field.spec.direction || other.spec.unit != field.spec.unit) {
                    unavailable(key, "'${field.path}' differs in direction or unit from the $broader value")
                }
                Limit(field, bound, field.spec.direction == Direction.DOWN_STRICTER, key, broader, crossScope = true)
            }
        }
    }

    private fun rowLimits(
        definition: VariationPointDefinition,
        fields: List<Field>,
        at: LocalDate,
    ): List<Limit> =
        fields.flatMap { field ->
            listOfNotNull(
                field.spec.max
                    ?.row
                    ?.let { it to true },
                field.spec.min
                    ?.row
                    ?.let { it to false },
            ).map { (row, ceiling) ->
                if (field.value ==
                    null
                ) {
                    unavailable(definition.key, "option '${field.option}' has no '${field.path}'")
                }
                val bound = rowNumber(definition.key, row, field.spec.unit, at)
                Limit(field, bound, ceiling, row, ScopeLevel.INSTALLATION, crossScope = false)
            }
        }

    /** The bounding row's value at installation scope: one option, one number, in [unit] (INV-Q-3202). */
    private fun rowNumber(
        key: VariationPointKey,
        row: VariationPointKey,
        unit: String?,
        at: LocalDate,
    ): BigDecimal {
        val bounding =
            catalogue.find(row) ?: unavailable(key, "the bounding row $row is not in the catalogue")
        if (ScopeLevel.INSTALLATION !in bounding.scopeLevels || bounding.installationParameter != null ||
            bounding.crossScope != null
        ) {
            unavailable(key, "$row is not a plain installation row")
        }
        val resolved = resolving(key) { resolve(row, PolicyScope.installation(), at) }
        val only = resolved.selected.singleOrNull() ?: unavailable(key, "$row resolves to several options")
        val spec = bounding.option(only.key)?.typedValue
        if (spec == null || !spec.type.numeric || spec.unit != unit) {
            unavailable(key, "$row option '${only.key}' is not a number in $unit")
        }
        val number =
            only.valueJson?.let { json -> walker.parse(row, json).takeIf { it.isNumber }?.decimalValue() }
                ?: spec.absentAs
        return number ?: unavailable(key, "$row option '${only.key}' has no number")
    }

    /** A broader value or bounding row that must be elected and has none bounds nothing knowable: refused. */
    private fun resolving(
        key: VariationPointKey,
        block: () -> ResolvedSetting,
    ): ResolvedSetting =
        try {
            block()
        } catch (expected: ElectionRequired) {
            unavailable(key, expected.message.orEmpty())
        }
}
