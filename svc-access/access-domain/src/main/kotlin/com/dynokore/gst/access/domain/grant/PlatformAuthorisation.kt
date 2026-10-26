package com.dynokore.gst.access.domain.grant

import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionMatrix
import java.time.Instant

/**
 * The answer of `authorise` for the scope `platform` (INV-REQ-862 behaviours 4 and 5, owner answer INV-Q-8687 A): the
 * operator-role grants of the platform tenant, read by the caller under the platform tenant binding. A pure function
 * of the grants, the matrix and the instant, so nothing is cached (INV-REQ-1214). Only a TENANT-level grant counts and
 * only a PERMITTED cell: a WITHIN_SCOPE cell or a missing cell denies.
 */
object PlatformAuthorisation {
    const val NO_GRANT = AuthorisationPolicy.NO_GRANT

    fun decide(
        platformGrants: List<Grant>,
        permission: Permission,
        matrix: PermissionMatrix,
        at: Instant,
    ): Decision {
        val covering =
            platformGrants.firstOrNull {
                it.status == GrantStatus.ACTIVE &&
                    it.window.isValidAt(at) &&
                    it.scope.level == ScopeLevel.TENANT &&
                    matrix.allowance(it.role, permission) == Allowance.PERMITTED
            }
        return if (covering == null) Decision.Deny(NO_GRANT) else Decision.Allow(covering.role)
    }
}
