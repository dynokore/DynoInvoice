package com.dynokore.gst.access.application.authorise

import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.domain.grant.AuthorisationPolicy
import com.dynokore.gst.access.domain.grant.Decision
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Target
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionMatrix
import java.time.Clock

/**
 * `authorise(principal, permission, target)`: role, permission and scope evaluated together against the live grant table,
 * never a cached tenant context (INV-REQ-859, INV-REQ-1214). Each call reads the grants again; this class keeps no state.
 * A target whose entity is unknown to the structure is denied (fail closed).
 */
class Authorise(
    private val grants: GrantRepository,
    private val directory: EntityDirectory,
    private val matrix: PermissionMatrix,
    private val clock: Clock,
) {
    fun handle(
        principal: IdentityRef,
        permission: Permission,
        target: Target,
    ): Decision {
        val membership = directory.membership(target.entity) ?: return Decision.Deny(UNKNOWN_ENTITY)
        return AuthorisationPolicy.decide(
            grants.findByIdentity(principal),
            permission,
            target,
            membership,
            matrix,
            clock.instant(),
        )
    }

    companion object {
        const val UNKNOWN_ENTITY = "NO_ENTITY"
    }
}
