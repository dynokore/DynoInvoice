package com.dynokore.gst.access.application.grant

import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow

/**
 * What the caller asks for, before any rule is applied. A missing scope is representable here so that it can be
 * refused.
 */
data class GrantRoleCommand(
    val tenant: TenantRef,
    val actor: String,
    val identity: IdentityRef,
    val granteeKind: GranteeKind,
    val roleWire: String,
    val scope: Scope?,
    val window: ValidityWindow,
)

sealed interface GrantRoleOutcome {
    /** The grant was created, or a repeat of the same (identity, role, scope) returned the original. */
    data class Granted(
        val grant: Grant,
        val repeated: Boolean,
    ) : GrantRoleOutcome

    /** A grant without a scope is invalid (INV-REQ-859 behaviour 4, INV-AC-1907). */
    data object ScopeMissing : GrantRoleOutcome

    /**
     * Assigning the second of `security.officer` and `tenant.admin` to one person in one tenant, with no recorded
     * sole-operator acknowledgement (INV-REQ-891, INV-AC-1931).
     */
    data object SoleOperatorAckRequired : GrantRoleOutcome

    /**
     * The grant would give a second person both roles while another person already holds both under the active
     * acknowledgement (INV-REQ-891, INV-AC-8288 case C2; default value `ONE_HOLDER_WITHDRAW_BLOCKED` of VP-81).
     */
    data object SecondHolderRefused : GrantRoleOutcome

    /** The role is not in the role catalogue of this installation's form (INV-NOTE-3 section 22.2, INV-REQ-888). */
    data class UnknownRole(
        val roleWire: String,
    ) : GrantRoleOutcome
}

sealed interface RevokeGrantOutcome {
    data class Revoked(
        val grant: Grant,
    ) : RevokeGrantOutcome

    /** Already revoked: a no-op (API-15). */
    data class AlreadyRevoked(
        val grant: Grant,
    ) : RevokeGrantOutcome

    data object NotFound : RevokeGrantOutcome
}
