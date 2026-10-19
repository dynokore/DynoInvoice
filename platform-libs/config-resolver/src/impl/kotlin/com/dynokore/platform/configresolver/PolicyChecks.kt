package com.dynokore.platform.configresolver

import java.time.LocalDate

/** The checks [JdbcPolicyWriter] makes on the key, the scope, the selection and its bounds (INV-Q-2930, INV-Q-3167). */
internal class PolicyChecks(
    private val catalogue: VariationPointCatalogue,
    private val validator: ValueValidator,
    private val bounds: ScopeBounds,
    private val writes: JdbcSettingWrites,
    private val constraints: ValueConstraints?,
) {
    /** Unknown and retired keys throw; a planned key cannot be set (INV-Q-2930, INV-REQ-1251). */
    fun settable(key: VariationPointKey): VariationPointDefinition {
        val definition = catalogue.require(key)
        when (definition.status) {
            VpStatus.RETIRED -> throw VariationPointRetired(key)
            VpStatus.PLANNED -> refuse(key, PolicyRefusal.PLANNED, "a planned variation point cannot be set")
            VpStatus.AVAILABLE -> Unit
        }
        return definition
    }

    /** The scope level comes first (INV-Q-2930, INV-AC-2227). */
    fun targetOf(
        definition: VariationPointDefinition,
        scope: PolicyScope,
        level: ScopeLevel,
    ): SettingTarget {
        if (level !in definition.scopeLevels) {
            refuse(definition.key, PolicyRefusal.SCOPE_NOT_ALLOWED, "the row allows ${definition.scopeLevels.sorted()}")
        }
        return scope.target(level)
    }

    /** Lifted only for what is implemented (INV-T-3176): anything [ScopeBounds] cannot evaluate is refused. */
    fun unsupported(definition: VariationPointDefinition) {
        bounds.unsupported(definition)?.let { refuse(definition.key, PolicyRefusal.NOT_SUPPORTED, it) }
    }

    /** [unsupported], then the selection against the row and the owning service, then its bounds. */
    fun selectionAndBounds(
        definition: VariationPointDefinition,
        scope: PolicyScope,
        target: SettingTarget,
        selected: List<SelectedOption>,
        onDate: LocalDate,
    ) {
        unsupported(definition)
        checkSelection(definition, target, selected, onDate)
        checkBounds(definition, scope, target, selected, onDate)
    }

    /**
     * A narrower value may not be looser than the broader value, nor pass the installation bound (INV-REQ-780
     * b1, INV-REQ-1248, INV-Q-3202/3204), on its effective date and on every later date a broader value or
     * the bounding row already takes effect. A tighter broader write is never refused: narrower values then
     * resolve capped (INV-Q-3206).
     */
    private fun checkBounds(
        definition: VariationPointDefinition,
        scope: PolicyScope,
        target: SettingTarget,
        selected: List<SelectedOption>,
        effectiveFrom: LocalDate,
    ) {
        if (!bounds.applies(definition)) return
        val key = definition.key
        val limiting =
            bounds.broaderLevels(definition, scope, target.level).map { key to scope.target(it) } +
                listOfNotNull(definition.installationParameter).map {
                    it to PolicyScope.installation().target(ScopeLevel.INSTALLATION)
                }
        val later =
            limiting
                .flatMap { (row, at) -> writes.versions(row, at).map { it.effectiveFrom } }
                .filter { it > effectiveFrom }
        (listOf(effectiveFrom) + later.distinct().sorted()).forEach { date ->
            val limits =
                try {
                    bounds.limits(definition, scope, target.level, selected, date)
                } catch (unavailable: BoundUnavailable) {
                    refuse(key, PolicyRefusal.BOUND_UNAVAILABLE, unavailable.message.orEmpty())
                }
            limits.firstOrNull { it.violatedBy(it.field.value) }?.let { refuseViolation(key, it, date) }
        }
    }

    /** Refuses the selection for the [limit] it breaks on [date]. */
    private fun refuseViolation(
        key: VariationPointKey,
        limit: ScopeBounds.Limit,
        date: LocalDate,
    ): Nothing {
        val side = if (limit.ceiling) "above" else "below"
        val where =
            "'${limit.field.option}' ${limit.field.path.ifEmpty { "value" }} " +
                "${limit.field.value?.toPlainString()} is $side"
        if (limit.crossScope) {
            refuse(
                key,
                PolicyRefusal.LOOSER_THAN_BROADER,
                "$where the ${limit.level} value ${limit.bound.toPlainString()} on $date",
            )
        } else {
            refuse(
                key,
                PolicyRefusal.BEYOND_INSTALLATION_BOUND,
                "$where ${limit.by} ${limit.bound.toPlainString()} on $date",
            )
        }
    }

    // Any failure of the owning service's port refuses (INV-Q-3167): the catch is deliberately wide.
    private fun checkSelection(
        definition: VariationPointDefinition,
        target: SettingTarget,
        selected: List<SelectedOption>,
        onDate: LocalDate,
    ) {
        if (!validator.check(definition, selected)) return
        val verdict =
            try {
                constraints?.check(target, definition.key, selected, onDate)
            } catch (expected: RuntimeException) {
                refuse(
                    definition.key,
                    PolicyRefusal.CONSTRAINT_REFUSED,
                    "the owning service's check failed: ${expected.javaClass.simpleName}",
                )
            }
        when (verdict) {
            ConstraintVerdict.Accept -> {}

            is ConstraintVerdict.Refuse -> {
                refuse(definition.key, PolicyRefusal.CONSTRAINT_REFUSED, verdict.reason)
            }

            null -> {
                refuse(
                    definition.key,
                    PolicyRefusal.CONSTRAINT_REFUSED,
                    "the owning service did not check the value",
                )
            }
        }
    }
}
