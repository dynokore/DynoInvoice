package com.dynokore.gst.masterdata.domain.identity

/** The roles that may give the second confirmation of an identity triple change (INV-REQ-1270, VP-09). */
enum class ConfirmerRole(
    val wire: String,
) {
    ENTITY_ADMIN("entity.admin"),
    TENANT_ADMIN("tenant.admin"),
    TAX_APPROVER("tax.approver"),
    ;

    companion object {
        fun ofWire(wire: String): ConfirmerRole? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The set of roles that may confirm (VP-09, a CONTROL, non-waivable). Default: another `entity.admin` or
 * `tenant.admin`; a tenant that chose it adds `tax.approver`. Whatever the set, it is never empty and never lifts the
 * rule that the confirming person differs from the proposing one.
 */
class ConfirmerSet(
    roles: Set<ConfirmerRole>,
) {
    val roles: Set<ConfirmerRole> = roles.toSet()

    init {
        require(this.roles.isNotEmpty()) { "the confirmer set is never empty" }
    }

    companion object {
        val DEFAULT = ConfirmerSet(setOf(ConfirmerRole.ENTITY_ADMIN, ConfirmerRole.TENANT_ADMIN))
    }
}

/** Why a second confirmation does not count. */
enum class ConfirmationRefusal {
    /** The confirming person is the proposing person, whichever role they confirm in (INV-AC-2254). */
    SAME_PERSON,

    /** The confirming subject holds no role of the tenant's confirmer set. */
    ROLE_NOT_ALLOWED,
}

/** The rule of INV-REQ-1007 and INV-REQ-1270, with no waiver. */
object ConfirmationRule {
    /**
     * [confirmer] is the authenticated subject of the call, never a body field. [permitted] is the answer of the
     * permission `entity-config.confirm` for that subject (INV-Q-10027); this rule holds no role name. The different
     * person rule comes first and is never waived.
     */
    fun check(
        proposer: PersonRef,
        confirmer: PersonRef,
        permitted: Boolean,
    ): ConfirmationRefusal? =
        when {
            confirmer.isSamePersonAs(proposer) -> ConfirmationRefusal.SAME_PERSON
            !permitted -> ConfirmationRefusal.ROLE_NOT_ALLOWED
            else -> null
        }
}
