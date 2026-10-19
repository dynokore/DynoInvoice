package com.dynokore.gst.access.domain.grant

import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import java.time.Instant

enum class GrantStatus { PENDING, ACTIVE, REVOKED }

/** Who a grant is given to (grantRole covers inviteUser and inviteExternalIdentity, FR-AF.20). */
enum class GranteeKind { STAFF, EXTERNAL, CROSS_TENANT }

/** How a grant was provisioned (INV-REQ-864 behaviour 3, INV-REQ-1263). */
enum class GrantSource { BY_HAND, DIRECTORY_GROUPS, SCIM }

/**
 * A grant is a triple of role, scope and validity window (INV-REQ-864). Immutable: a change returns a new instance (CS-07).
 * [version] is the optimistic-concurrency version of the stored row.
 */
class Grant(
    val ref: GrantRef,
    val tenant: TenantRef,
    val identity: IdentityRef,
    val role: Role,
    val scope: Scope,
    val window: ValidityWindow,
    val status: GrantStatus,
    val source: GrantSource,
    val version: Long,
) {
    init {
        require(version >= 0) { "version must not be negative" }
    }

    /** Whether this one grant authorises [permission] on [target] at [at]: role, scope and validity together, no other grant. */
    fun authorises(
        permission: Permission,
        target: Target,
        membership: EntityMembership,
        matrix: PermissionMatrix,
        at: Instant,
    ): Boolean =
        status == GrantStatus.ACTIVE &&
            window.isValidAt(at) &&
            matrix.allowance(role, permission) in setOf(Allowance.PERMITTED, Allowance.WITHIN_SCOPE) &&
            scope.covers(target, membership)

    /** The same grant, revoked. Revoking a revoked grant is a no-op that returns this instance (idempotent, API-15). */
    fun revoked(): Grant =
        if (status == GrantStatus.REVOKED) {
            this
        } else {
            Grant(ref, tenant, identity, role, scope, window, GrantStatus.REVOKED, source, version + 1)
        }

    companion object {
        /** A new active grant at version 0. */
        fun issue(
            ref: GrantRef,
            tenant: TenantRef,
            identity: IdentityRef,
            role: Role,
            scope: Scope,
            window: ValidityWindow,
            source: GrantSource = GrantSource.BY_HAND,
        ): Grant = Grant(ref, tenant, identity, role, scope, window, GrantStatus.ACTIVE, source, 0)
    }
}

/** The answer to an authorisation question (INV-REQ-859). The role that authorised is named; a denial names a reason. */
sealed interface Decision {
    data class Allow(
        val role: Role,
    ) : Decision

    data class Deny(
        val reasonCode: String,
    ) : Decision
}

/**
 * Evaluates role, permission and scope together over the grants it is given (INV-REQ-859 behaviour 1, INV-REQ-864
 * behaviour 5): a grant authorises an action only when it alone covers it. It holds no state, so it cannot serve a cached
 * decision (INV-REQ-1214).
 */
object AuthorisationPolicy {
    const val NO_GRANT = "NO_GRANT"

    fun decide(
        grants: List<Grant>,
        permission: Permission,
        target: Target,
        membership: EntityMembership,
        matrix: PermissionMatrix,
        at: Instant,
    ): Decision {
        val covering = grants.firstOrNull { it.authorises(permission, target, membership, matrix, at) }
        return if (covering == null) Decision.Deny(NO_GRANT) else Decision.Allow(covering.role)
    }
}
