package com.dynokore.gst.tenancy.adapters.workspace

import com.dynokore.gst.tenancy.application.workspace.TenantAuthority
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * [TenantAuthority] over the authority replica (INV-Q-10027, INV-Q-11901 A): true only when `decideTenant` allows the
 * person (kind HUMAN) a TENANT-scope grant of exactly the permission key, answered from the local replica. With no
 * replica bound, or a DEGRADED answer, the person is refused (fail closed).
 */
class ReplicaTenantAuthority(
    private val replica: AuthorityReplica?,
    private val tenants: TenantContext,
) : TenantAuthority {
    override fun holds(
        person: String,
        permission: String,
    ): Boolean {
        val decision =
            replica?.decideTenant(tenants.current(), Principal(person, PrincipalKind.HUMAN), permission)
                ?: return false
        return decision.allowed && decision.source == DecisionSource.LOCAL
    }
}
