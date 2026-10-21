package com.dynokore.gst.access.domain.role

/** Where the landing role of a person came from (INV-REQ-2756, VP-73). */
enum class LandingSource { PERSONAL_CHOICE, TENANT_PRECEDENCE, ROLE_ORDER_PRESET }

/** The role whose landing screen a person lands on, and which rule chose it. */
data class Landing(
    val role: Role,
    val source: LandingSource,
)

/**
 * Which landing screen a person who holds several roles gets (INV-REQ-2756): their own choice, else the tenant's
 * role precedence, else the role order of UI inventory section 4.1. Only roles the person holds are candidates. It
 * decides a landing screen only and never reads or changes a permission.
 */
object LandingPolicy {
    /** The role order of UI inventory section 4.1 (the rows of its table; `counterparty` is no role of this service). */
    val ROLE_ORDER: List<Role> =
        listOf(
            Role.TENANT_ADMIN,
            Role.ENTITY_ADMIN,
            Role.SECURITY_OFFICER,
            Role.BILLING_CONTACT,
            Role.TAX_OPERATOR,
            Role.TAX_APPROVER,
            Role.FINANCE_VIEWER,
            Role.INTEGRATION_ENGINEER,
            Role.AUDITOR,
            Role.AGENT_OPERATOR,
            Role.CLIENT_SIGNATORY,
            Role.DEPLOYMENT_OPERATOR,
            Role.IMPLEMENTATION_CONSULTANT,
            Role.PLATFORM_SUPPORT,
            Role.PLATFORM_ENGINEER,
            Role.REGULATORY_AUTHOR,
            Role.REGULATORY_APPROVER,
            Role.RELEASE_MANAGER,
            Role.COMMERCIAL_ADMIN,
        )

    /** Whether [role] may be chosen: only a role the person holds is a candidate. */
    fun isCandidate(
        held: Set<Role>,
        role: Role,
    ): Boolean = role in held

    /**
     * The landing of a person holding [held], or null when they hold no role. A [choice] of a role no longer held is
     * ignored. A [precedence] that names none of the held roles falls back to the preset order.
     */
    fun resolve(
        held: Set<Role>,
        choice: Role?,
        precedence: List<Role>?,
    ): Landing? {
        val byPrecedence = precedence?.firstOrNull { it in held }
        val byPreset = ROLE_ORDER.firstOrNull { it in held } ?: held.minByOrNull { it.ordinal }
        return when {
            choice != null && choice in held -> Landing(choice, LandingSource.PERSONAL_CHOICE)
            byPrecedence != null -> Landing(byPrecedence, LandingSource.TENANT_PRECEDENCE)
            byPreset != null -> Landing(byPreset, LandingSource.ROLE_ORDER_PRESET)
            else -> null
        }
    }
}
