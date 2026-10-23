package com.dynokore.gst.access.domain.breakglass

import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.role.Role

/** What may be done under break-glass: viewing, readiness, drain and replay, nothing else (INV-REQ-896 behaviour 3). */
enum class BreakGlassAction { VIEW, READINESS, DRAIN, REPLAY }

/**
 * The pairing and session length rules of the default break-glass setting (INV-REQ-1269 behaviours 1 and 2): two
 * different people, one of them holding `deployment.operator` or `tenant.admin` and another holding `tax.approver`;
 * 4 hours by default, 1 to 8 hours at most. There is no sole-operator path: a person never fills both places.
 */
object BreakGlassRules {
    const val DEFAULT_HOURS = 4
    const val MIN_HOURS = 1
    const val MAX_HOURS = 8

    /** The four members, as a set: a session permits exactly these. */
    val PERMITTED_ACTIONS: Set<BreakGlassAction> = BreakGlassAction.entries.toSet()

    fun isSessionHours(hours: Int): Boolean = hours in MIN_HOURS..MAX_HOURS

    /** Whether the distinct people in [roles] fill both places of [pairing] with two different people. */
    fun pairHolds(
        roles: Map<IdentityRef, Set<Role>>,
        pairing: BreakGlassPairing = BreakGlassPairing.DEFAULT,
    ): Boolean =
        roles.any { (first, firstRoles) ->
            firstRoles.any { it in pairing.first } &&
                roles.any { (second, secondRoles) -> second != first && secondRoles.any { it in pairing.second } }
        }
}

/**
 * The two places of a break-glass unlock (VP-08): one person holds a role of [first], another holds a role of [second].
 * The default is `deployment.operator` or `tenant.admin` together with `tax.approver`; the option OTHER_NAMED_PAIR
 * names two roles. Always two different people, whatever the pairing.
 */
data class BreakGlassPairing(
    val first: Set<Role>,
    val second: Set<Role>,
) {
    init {
        require(first.isNotEmpty() && second.isNotEmpty()) { "both places of the pairing need a role" }
    }

    companion object {
        val DEFAULT = BreakGlassPairing(setOf(Role.DEPLOYMENT_OPERATOR, Role.TENANT_ADMIN), setOf(Role.TAX_APPROVER))

        fun named(
            one: Role,
            other: Role,
        ) = BreakGlassPairing(setOf(one), setOf(other))
    }
}
