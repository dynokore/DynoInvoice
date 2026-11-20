package com.dynokore.gst.evidence.adapters.http

import com.dynokore.gst.evidence.application.cases.CaseAccessDenied
import com.dynokore.gst.evidence.application.cases.CaseAuthorizer
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.tenancykernel.TenantId

/**
 * The [CaseAuthorizer] over `AuthorityReplica.decideTenant` (INV-Q-11686 A, INV-Q-11688 A). The case operations name no
 * entity, so a permission is held only through a TENANT-scope grant. It asks for the permission key only, never a role
 * name (INV-Q-10027). Fail closed: a decision that is not `allowed` with source LOCAL, and any failure of the decision,
 * refuse.
 */
class ReplicaCaseAuthorizer(
    private val replica: AuthorityReplica,
) : CaseAuthorizer {
    @Suppress("TooGenericExceptionCaught") // any failure of the decision refuses: fail closed
    override fun require(
        tenant: TenantId,
        principal: Principal,
        permission: String,
    ) {
        val permitted =
            try {
                val decision = replica.decideTenant(tenant, principal, permission)
                decision.allowed && decision.source == DecisionSource.LOCAL
            } catch (_: Exception) {
                false
            }
        if (!permitted) throw CaseAccessDenied(permission)
    }
}
