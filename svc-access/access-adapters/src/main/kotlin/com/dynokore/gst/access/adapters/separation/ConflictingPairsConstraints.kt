package com.dynokore.gst.access.adapters.separation

import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.platform.configresolver.ConstraintVerdict
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SettingTarget
import com.dynokore.platform.configresolver.ValueConstraints
import com.dynokore.platform.configresolver.VariationPointKey
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate

/**
 * What only access-svc can check in the value of VP-82 (INV-REQ-9271, ruling INV-Q-9202, INV-Q-3167): it accepts a
 * value only if [VariationPointConflictingPairs] can read it, and in addition refuses `NO_EXTRA_PAIRS` with a value and
 * a role outside this installation's catalogue (the owner's tighten-only setting). Any other variation point gets no
 * verdict. It never throws: a value that does not parse is a refusal.
 */
class ConflictingPairsConstraints(
    private val catalogue: RoleCatalogue,
    private val mapper: ObjectMapper,
) : ValueConstraints {
    override fun check(
        target: SettingTarget,
        key: VariationPointKey,
        selected: List<SelectedOption>,
        onDate: LocalDate,
    ): ConstraintVerdict? = if (key.value == "VP-82") verdictOf(selected) else null

    private fun verdictOf(selected: List<SelectedOption>): ConstraintVerdict {
        val option = selected.singleOrNull() ?: return ConstraintVerdict.Refuse(ONE_OPTION)
        return when (option.key) {
            NO_EXTRA_PAIRS -> {
                if (option.valueJson == null) ConstraintVerdict.Accept else ConstraintVerdict.Refuse(NO_VALUE)
            }

            CONFLICTING_PAIRS -> {
                val json = option.valueJson ?: return ConstraintVerdict.Refuse(LIST_REQUIRED)
                pairsVerdict(json)
            }

            else -> {
                ConstraintVerdict.Refuse(UNKNOWN_OPTION)
            }
        }
    }

    private fun pairsVerdict(json: String): ConstraintVerdict {
        val list = runCatching { mapper.readTree(json) }.getOrNull() ?: return ConstraintVerdict.Refuse(NOT_JSON)
        if (!list.isArray || list.size() == 0) return ConstraintVerdict.Refuse(LIST_REQUIRED)
        for (index in 0 until list.size()) {
            val refusal = pairRefusal(list.get(index))
            if (refusal != null) return ConstraintVerdict.Refuse(refusal)
        }
        return ConstraintVerdict.Accept
    }

    /** The reason this pair is refused, or null when it is a pair of two different roles of the catalogue. */
    private fun pairRefusal(node: JsonNode): String? {
        if (!node.isObject || node.propertyNames().asSequence().toSet() != FIELDS) return PAIR_SHAPE
        val first = node.get("first")
        val second = node.get("second")
        if (!first.isString || !second.isString) return NOT_A_STRING
        val firstRole = Role.ofWire(first.asString())
        val secondRole = Role.ofWire(second.asString())
        return when {
            firstRole == null || secondRole == null -> UNKNOWN_ROLE
            !catalogue.contains(firstRole) || !catalogue.contains(secondRole) -> NOT_IN_CATALOGUE
            firstRole == secondRole -> SAME_ROLE
            else -> null
        }
    }

    companion object {
        const val ONE_OPTION = "VP-82 takes exactly one selected option"
        const val UNKNOWN_OPTION = "VP-82 selects an option this service does not know"
        const val NO_VALUE = "NO_EXTRA_PAIRS takes no value"
        const val LIST_REQUIRED = "CONFLICTING_PAIRS needs a non-empty list of pairs"
        const val NOT_JSON = "the list of pairs is not JSON"
        const val PAIR_SHAPE = "each pair must be an object with exactly the fields first and second"
        const val NOT_A_STRING = "each role of a pair must be a string"
        const val UNKNOWN_ROLE = "a pair names a role that is no role of this service"
        const val NOT_IN_CATALOGUE = "a pair names a role that is not in this installation's catalogue"
        const val SAME_ROLE = "a pair names one role twice"
        private const val NO_EXTRA_PAIRS = "NO_EXTRA_PAIRS"
        private const val CONFLICTING_PAIRS = "CONFLICTING_PAIRS"
        private val FIELDS = setOf("first", "second")
    }
}
