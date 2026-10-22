package com.dynokore.gst.tenancy.adapters.policy

import com.dynokore.gst.tenancy.application.status.PhaseAssessmentStore
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.platform.configresolver.ConstraintVerdict
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SettingTarget
import com.dynokore.platform.configresolver.ValueConstraints
import com.dynokore.platform.configresolver.VariationPointKey
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate

/**
 * The ValueConstraints port of config-resolver for the variation points tenancy-svc owns (INV-Q-8729 A). Deny by
 * default: a variation point other than VP-51 is refused, and VP-51 refuses an `EARLIER_VOLUNTARY_DATE` that is later
 * than the mandatory date stored by assessPhase for the entity (INV-REQ-1312, INV-AC-2300), that has no entity
 * to bound it, or whose entity has no stored mandatory date. The mandatory date and the communicated date of a group
 * need no bound.
 */
class ImplementationDateConstraints(
    private val structure: StructureStore,
    private val assessments: PhaseAssessmentStore,
    private val mapper: ObjectMapper,
) : ValueConstraints {
    override fun check(
        target: SettingTarget,
        key: VariationPointKey,
        selected: List<SelectedOption>,
        onDate: LocalDate,
    ): ConstraintVerdict {
        if (key != TenancyVariationPoints.IMPLEMENTATION_DATE_BASIS) {
            return ConstraintVerdict.Refuse("tenancy-svc has no value constraint for $key")
        }
        return selected.firstNotNullOfOrNull { option -> refusal(target, option) } ?: ConstraintVerdict.Accept
    }

    private fun refusal(
        target: SettingTarget,
        option: SelectedOption,
    ): ConstraintVerdict.Refuse? =
        when (option.key) {
            TenancyVariationPoints.MANDATORY_DATE, TenancyVariationPoints.IRAS_COMMUNICATED_DATE -> {
                null
            }

            TenancyVariationPoints.EARLIER_VOLUNTARY_DATE -> {
                voluntaryRefusal(target, option)
            }

            else -> {
                ConstraintVerdict.Refuse("VP-51 has no option ${option.key}")
            }
        }

    private fun voluntaryRefusal(
        target: SettingTarget,
        option: SelectedOption,
    ): ConstraintVerdict.Refuse? {
        val date = option.valueJson?.let { LocalDate.parse(mapper.readTree(it).asString()) }
        val entity = target.entity?.takeIf { target.level == ScopeLevel.ENTITY }?.let(structure::entity)
        val mandatory = entity?.let { assessments.latest(it.id) }?.assessment?.mandatoryDate
        return when {
            date == null -> {
                ConstraintVerdict.Refuse("an earlier voluntary date needs its date")
            }

            entity == null -> {
                ConstraintVerdict.Refuse(
                    "an earlier voluntary date is bounded by one entity's mandatory date",
                )
            }

            mandatory == null -> {
                ConstraintVerdict.Refuse("no mandatory date is stored for the entity: run assessPhase")
            }

            date.isAfter(
                mandatory,
            ) -> {
                ConstraintVerdict.Refuse("the date $date is later than the mandatory date $mandatory")
            }

            else -> {
                null
            }
        }
    }
}
