package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.breakglass.BREAK_GLASS_RESEAL
import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.serviceruntime.policy.PolicyRoleResolver
import com.dynokore.platform.tenancykernel.TenantContext

/** The caller check of `resealBreakGlass`: the permission `break-glass.reseal`, decided per request (INV-Q-16100 A). */
fun interface ResealPermissionCheck {
    fun mayReseal(): Boolean
}

/**
 * Allows when one installation-level role of the bound actor (the configured `access.installation-roles`, the source
 * `setPolicy` uses, INV-Q-8789 A) has `break-glass.reseal` PERMITTED in the permission matrix of the deployed form.
 * No role is named here. Anything else, a failed read included, refuses; an exception is never turned into true. No
 * pair membership is checked (INV-Q-8895 is open).
 */
class ResolvedResealPermissionCheck(
    private val tenants: TenantContext,
    private val roles: PolicyRoleResolver,
    private val matrix: PermissionMatrix,
) : ResealPermissionCheck {
    override fun mayReseal(): Boolean =
        runCatching {
            val actor = tenants.currentActor() ?: return false
            roles
                .rolesOf(actor, tenants.current(), ScopeLevel.INSTALLATION, null, null)
                .mapNotNull { Role.ofWire(it) }
                .any { matrix.allowance(it, BREAK_GLASS_RESEAL) == Allowance.PERMITTED }
        }.getOrDefault(false)
}
