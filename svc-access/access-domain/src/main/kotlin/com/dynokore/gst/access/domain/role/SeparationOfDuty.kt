package com.dynokore.gst.access.domain.role

import com.dynokore.gst.access.domain.grant.IdentityRef

/**
 * Nobody holds `security.officer` and `tenant.admin` (INV-REQ-891, INV-REQ-890), or `tax.approver` and `tenant.admin`
 * (INV-REQ-860 behaviour 3: whoever exercises overrides does not administer the override policy), in the same tenant
 * without the recorded sole-operator acknowledgement. Assigning the second of a pair is the refused step.
 */
object SeparationOfDuty {
    private val CONFLICTING = setOf(Role.SECURITY_OFFICER, Role.TENANT_ADMIN)
    private val OVERRIDE_PAIR = setOf(Role.TAX_APPROVER, Role.TENANT_ADMIN)
    private val PAIRS = listOf(CONFLICTING, OVERRIDE_PAIR)

    /** Whether [adding] to a person who already holds [held] completes a conflicting pair. */
    fun completesConflict(
        held: Set<Role>,
        adding: Role,
    ): Boolean = PAIRS.any { pair -> adding in pair && (pair - adding).any { it in held } }

    /** Whether [adding] completes the `security.officer` and `tenant.admin` pair, the one VP-81 governs. */
    fun completesOfficerPair(
        held: Set<Role>,
        adding: Role,
    ): Boolean = adding in CONFLICTING && (CONFLICTING - adding).any { it in held }

    /** The people who hold both conflicting roles, from the holders of each. */
    fun holdingBoth(
        officers: Set<IdentityRef>,
        admins: Set<IdentityRef>,
    ): Set<IdentityRef> = officers intersect admins
}
