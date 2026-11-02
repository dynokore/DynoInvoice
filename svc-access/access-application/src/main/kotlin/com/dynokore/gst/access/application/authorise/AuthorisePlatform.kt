package com.dynokore.gst.access.application.authorise

import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.domain.grant.Decision
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.PlatformAuthorisation
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionMatrix
import java.time.Clock

/** Runs a read in one transaction bound to the platform tenant (the adapter binds it; this layer cannot). */
interface PlatformRead {
    fun <T> read(block: () -> T): T
}

/**
 * `authorise` for the scope `platform`: the grants of the principal are read live, in one transaction under the
 * platform tenant binding, and decided by [PlatformAuthorisation]. No entity is looked up; a customer-tenant grant is
 * invisible because the read is bound to the platform tenant. Keeps no state between calls (INV-REQ-1214).
 */
class AuthorisePlatform(
    private val platform: PlatformRead,
    private val grants: GrantRepository,
    private val matrix: PermissionMatrix,
    private val clock: Clock,
    private val operators: OperatorOverlay,
) {
    fun handle(
        principal: IdentityRef,
        permission: Permission,
    ): Decision {
        val now = clock.instant()
        // the grants and the invitation records are read in one platform-bound transaction
        return platform.read {
            val base = PlatformAuthorisation.decide(grants.findByIdentity(principal), permission, matrix, now)
            operators.apply(base, principal, permission, null, now)
        }
    }
}
