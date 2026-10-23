package com.dynokore.gst.access.adapters.breakglass

import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.platform.configresolver.ConstraintVerdict
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SettingTarget
import com.dynokore.platform.configresolver.ValueConstraints
import com.dynokore.platform.configresolver.VariationPointKey
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate

/**
 * What only access-svc can check in the value of VP-08 (ruling INV-Q-3167): the `pair` of the option OTHER_NAMED_PAIR
 * names exactly two roles of this installation's catalogue (the same role twice is allowed: two different people hold
 * it at unlock). Any other variation point is not this service's to check here and gets no verdict.
 */
class BreakGlassPairConstraints(
    private val catalogue: RoleCatalogue,
    private val mapper: ObjectMapper,
) : ValueConstraints {
    override fun check(
        target: SettingTarget,
        key: VariationPointKey,
        selected: List<SelectedOption>,
        onDate: LocalDate,
    ): ConstraintVerdict? =
        if (key == VP_08) {
            verdictOf(selected.singleOrNull { it.key == OTHER_NAMED_PAIR }?.valueJson)
        } else {
            null
        }

    private fun verdictOf(pair: String?): ConstraintVerdict {
        if (pair == null) return ConstraintVerdict.Accept
        val node = runCatching { mapper.readTree(pair).get(PAIR) }.getOrNull()
        val roles =
            if (node != null && node.isArray && node.size() == 2) {
                (0 until 2).map { i ->
                    node
                        .get(i)
                        .takeIf { it.isString }
                        ?.asString()
                        ?.let { Role.ofWire(it) }
                }
            } else {
                null
            }
        return when {
            roles == null -> {
                ConstraintVerdict.Refuse("the pair must name exactly two roles")
            }

            roles.any { it == null || !catalogue.contains(it) } -> {
                ConstraintVerdict.Refuse("the pair names a role that is not in this installation's catalogue")
            }

            else -> {
                ConstraintVerdict.Accept
            }
        }
    }

    private companion object {
        val VP_08 = VariationPointKey("VP-08")
        const val OTHER_NAMED_PAIR = "OTHER_NAMED_PAIR"
        const val PAIR = "pair"
    }
}

/** The first verdict among [checks]: each one answers for its own variation point and gives none for another. */
class ValueConstraintsChain(
    private val checks: List<ValueConstraints>,
) : ValueConstraints {
    override fun check(
        target: SettingTarget,
        key: VariationPointKey,
        selected: List<SelectedOption>,
        onDate: LocalDate,
    ): ConstraintVerdict? = checks.firstNotNullOfOrNull { it.check(target, key, selected, onDate) }
}
