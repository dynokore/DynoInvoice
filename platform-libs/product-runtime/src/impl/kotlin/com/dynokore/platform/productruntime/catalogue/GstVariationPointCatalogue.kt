package com.dynokore.platform.productruntime.catalogue

import com.dynokore.gst.catalogue.BoundValue
import com.dynokore.gst.catalogue.TypedValue
import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.catalogue.VpConstraint
import com.dynokore.gst.catalogue.VpOption
import com.dynokore.platform.configresolver.Acknowledger
import com.dynokore.platform.configresolver.AcknowledgerCondition
import com.dynokore.platform.configresolver.Bound
import com.dynokore.platform.configresolver.CatalogueListingUnavailable
import com.dynokore.platform.configresolver.DeploymentForm
import com.dynokore.platform.configresolver.Direction
import com.dynokore.platform.configresolver.OptionSpec
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.Strictness
import com.dynokore.platform.configresolver.TypedValueSpec
import com.dynokore.platform.configresolver.ValueType
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointDefinition
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpClass
import com.dynokore.platform.configresolver.VpModifier
import com.dynokore.platform.configresolver.VpStatus

/**
 * config-resolver's [VariationPointCatalogue] over the generated gst-catalogue `VariationPoint` (STK-61, CS-28;
 * INV-Q-2934, INV-Q-3119): every registered row, mapped unchanged, and nothing else. A key the catalogue does not
 * register is not found, so config-resolver refuses it (INV-REQ-1242 b1).
 *
 * The mapping (coordinator ruling INV-Q-3854):
 *  - an acknowledger naming several roles becomes one config-resolver [Acknowledger] per role, each with the same
 *    condition and `distinctFromSetter`; a `rolesByForm` entry naming more than one role in a form has no
 *    single-role equivalent, so that option gets no acknowledgers and config-resolver refuses it (fail closed);
 *  - `crossOption` carries every constraint record of the row (constraints, cross-row and cross-option alike), one
 *    per line as `<id> <kind>: <rule> (refused: <refused step>)`, and is null when the row has none (INV-Q-3167);
 *  - `crossScope` is the catalogue's text as is; `installationBound` is true when the catalogue declares an
 *    installation parameter, and `installationParameter` is the row its reference names (INV-Q-3173, INV-Q-3202);
 *  - `secondApprover`, `pendingQuestions` and a typed value's `name`, `filter` and `comparedWith` have no
 *    config-resolver field and are not carried.
 *
 * [ownerService] is the running service (`gst.service`): [keys] lists the rows it owns, which it seeds and checks
 * at start-up (INV-Q-3136). Without it the binding cannot list its rows.
 */
class GstVariationPointCatalogue(
    private val ownerService: String? = null,
) : VariationPointCatalogue {
    private val definitions: Map<VariationPointKey, VariationPointDefinition> =
        VariationPoint.entries.associate { VariationPointKey(it.id) to definition(it) }

    private val owners: Map<VariationPointKey, String> = VariationPoint.entries.associate { VariationPointKey(it.id) to it.owner }

    override fun find(key: VariationPointKey): VariationPointDefinition? = definitions[key]

    override fun keys(): Set<VariationPointKey> {
        val service = ownerService ?: throw CatalogueListingUnavailable()
        return owners.filterValues { it == service }.keys
    }

    /** Every registered row, in catalogue order. */
    fun all(): Collection<VariationPointDefinition> = definitions.values

    companion object {
        internal fun definition(vp: VariationPoint): VariationPointDefinition = VariationPointDefinition(
            key = VariationPointKey(vp.id),
            vpClass = VpClass.valueOf(vp.vpClass.name),
            modifiers = vp.classModifiers.mapTo(linkedSetOf()) { VpModifier.valueOf(it.name) },
            status = VpStatus.valueOf(vp.status.name),
            scopeLevels = vp.scopeLevels.orEmpty().mapTo(linkedSetOf()) { ScopeLevel.valueOf(it.name) },
            setBy = vp.setBy.toCollection(linkedSetOf()),
            options = vp.optionSpecs.orEmpty().map(::option),
            defaultByForm = vp.defaultByForm.mapKeys { DeploymentForm.valueOf(it.key) },
            mustBeElected = vp.mustBeElected,
            multiSelect = vp.multiSelect,
            exclusiveKeys = vp.exclusiveKeys.toCollection(linkedSetOf()),
            formDependence = vp.formDependence,
            crossOption = crossOption(vp.constraints),
            crossScope = vp.crossScope,
            installationBound = vp.installationParameter != null || vp.installationParameterRef != null,
            installationParameter = vp.installationParameterRef?.row?.let(::VariationPointKey),
        )

        fun crossOption(constraints: List<VpConstraint>): String? = constraints
            .joinToString("\n") { "${it.id} ${it.kind.name}: ${it.rule} (refused: ${it.refused})" }
            .ifEmpty { null }

        private fun option(option: VpOption): OptionSpec {
            val typed = option.typedValue?.let(::typedValue)
            return OptionSpec(
                key = option.key,
                strictness = Strictness.valueOf(option.strictness.name),
                typed = typed != null,
                typedValue = typed,
                acknowledgers = acknowledgers(option),
            )
        }

        fun acknowledgers(option: VpOption): List<Acknowledger> {
            val declared = option.acknowledgedBy
            if (declared.any { ack -> ack.rolesByForm.values.any { it.size > 1 } }) return emptyList()
            return declared.flatMap { ack ->
                val condition = AcknowledgerCondition.valueOf(ack.condition.name)
                if (ack.rolesByForm.isNotEmpty()) {
                    val byForm = ack.rolesByForm.entries.associate { DeploymentForm.valueOf(it.key.name) to it.value.single() }
                    listOf(Acknowledger(condition = condition, distinctFromSetter = ack.distinctFromSetter, roleByForm = byForm))
                } else {
                    ack.roles.map { Acknowledger(it, condition, ack.distinctFromSetter) }
                }
            }
        }

        private fun typedValue(value: TypedValue): TypedValueSpec = TypedValueSpec(
            type = ValueType.valueOf(value.type.name),
            unit = value.unit,
            min = value.min?.let(::bound),
            max = value.max?.let(::bound),
            minExclusive = value.minExclusive?.let(::bound),
            direction = value.direction?.let { Direction.valueOf(it.name) } ?: Direction.NONE,
            values = value.values.orEmpty(),
            fields = value.fields.orEmpty().mapValues { typedValue(it.value) },
            item = value.item?.let(::typedValue),
            key = value.key?.let(::typedValue),
            value = value.value?.let(::typedValue),
            minItems = value.minItems,
            maxItems = value.maxItems,
            length = value.length?.let(::bound),
            distinct = value.distinct ?: false,
            ordered = value.ordered ?: false,
            ref = value.ref,
            rule = value.rule,
            requires = value.requires,
            default = value.default?.let(::bound),
        )

        private fun bound(value: BoundValue): Bound = Bound(
            number = value.number,
            named = value.symbolic,
            row = value.row?.let(::VariationPointKey),
        )
    }
}
