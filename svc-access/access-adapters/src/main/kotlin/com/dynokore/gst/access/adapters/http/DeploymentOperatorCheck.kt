package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.serviceruntime.policy.PolicyRoleResolver
import com.dynokore.platform.tenancykernel.TenantContext

/** The caller check of `resealBreakGlass`: the published role is `deployment.operator` at installation scope. */
fun interface DeploymentOperatorCheck {
    fun isDeploymentOperator(): Boolean
}

/**
 * Allows when the bound actor holds `deployment.operator` at the INSTALLATION level by the same resolver that answers
 * `setPolicy` (the configured `access.installation-roles`, INV-Q-8789 A). Anything else, a failed read included,
 * refuses; an exception is never turned into true. No pair membership is checked (INV-Q-8895 is open).
 */
class ResolvedDeploymentOperatorCheck(
    private val tenants: TenantContext,
    private val roles: PolicyRoleResolver,
) : DeploymentOperatorCheck {
    override fun isDeploymentOperator(): Boolean =
        runCatching {
            val actor = tenants.currentActor() ?: return false
            Role.DEPLOYMENT_OPERATOR.wire in
                roles.rolesOf(actor, tenants.current(), ScopeLevel.INSTALLATION, null, null)
        }.getOrDefault(false)
}
