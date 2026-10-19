package com.dynokore.gst.access.application.grant

import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow

/** What the caller asks for, before any rule is applied. A missing scope is representable here so that it can be refused. */
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

    /** The role is not in the role catalogue (INV-NOTE-3 section 22.2). */
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
