package com.dynokore.gst.access.domain.grant

import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import java.time.Instant

/**
 * What a caller's own grants say about the whole tenant: pure functions of the grants, the tenant, the matrix and the
 * instant, so nothing is cached (INV-REQ-1214).
 */
object TenantAuthority {
    /** The permission that expresses "manages users and grants" for `tenant.admin` (INV-REQ-873, INV-T-7987). */
    val USER_MANAGE = Permission("user.manage")

    /** Whether one grant is live at [at] and covers the whole of [tenant]; any other scope level does not count. */
    private fun Grant.liveTenantWide(
        tenant: TenantRef,
        at: Instant,
    ): Boolean = status == GrantStatus.ACTIVE && window.isValidAt(at) && scope == Scope.Tenant(tenant)

    /**
     * True when one live grant of [tenant] scope has a role whose `user.manage` cell is PERMITTED in [matrix]. A
     * WITHIN_SCOPE cell or a missing cell does not count.
     */
    fun managesGrants(
        grants: List<Grant>,
        tenant: TenantRef,
        matrix: PermissionMatrix,
        at: Instant,
    ): Boolean =
        grants.any {
            it.liveTenantWide(tenant, at) && matrix.allowance(it.role, USER_MANAGE) == Allowance.PERMITTED
        }

    /** The roles of the grants that are live at [at] and of [tenant] scope. */
    fun tenantRoles(
        grants: List<Grant>,
        tenant: TenantRef,
        at: Instant,
    ): Set<Role> = grants.filter { it.liveTenantWide(tenant, at) }.map { it.role }.toSet()
}
