package com.dynokore.gst.access.adapters.landing

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
 * What only access-svc can check in the value of VP-73 (ruling INV-Q-3167): the TENANT_PRECEDENCE list names roles of
 * this installation's catalogue, each once. Any other variation point is not this service's to check, so it gets no
 * verdict and `setPolicy` refuses it (fail closed).
 */
class RolePrecedenceConstraints(
    private val catalogue: RoleCatalogue,
    private val mapper: ObjectMapper,
) : ValueConstraints {
    override fun check(
        target: SettingTarget,
        key: VariationPointKey,
        selected: List<SelectedOption>,
        onDate: LocalDate,
    ): ConstraintVerdict? =
        if (key == VP_73) {
            verdictOf(
                selected
                    .singleOrNull {
                        it.key == TENANT_PRECEDENCE
                    }?.valueJson,
            )
        } else {
            null
        }

    private fun verdictOf(json: String?): ConstraintVerdict {
        if (json == null) return ConstraintVerdict.Accept
        val roles = rolesOf(json)
        return when {
            roles == null -> {
                ConstraintVerdict.Refuse("the precedence must be a list of role keys")
            }

            roles.any { it == null || !catalogue.contains(it) } -> {
                ConstraintVerdict.Refuse("the precedence names a role that is not in this installation's catalogue")
            }

            roles.toSet().size != roles.size -> {
                ConstraintVerdict.Refuse("the precedence names a role twice")
            }

            else -> {
                ConstraintVerdict.Accept
            }
        }
    }

    /** The roles of a JSON list of role keys (a null for an item that is no known role), or null when it is no list. */
    private fun rolesOf(json: String): List<Role?>? {
        val node = runCatching { mapper.readTree(json) }.getOrNull()?.takeIf { it.isArray } ?: return null
        return (0 until node.size()).map { index ->
            val item = node.get(index)
            if (item.isString) item.asString()?.let { Role.ofWire(it) } else null
        }
    }

    private companion object {
        val VP_73 = VariationPointKey("VP-73")
        const val TENANT_PRECEDENCE = "TENANT_PRECEDENCE"
    }
}
