package com.dynokore.gst.masterdata.adapters.permission

import com.dynokore.gst.masterdata.application.permission.TenantPermission
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * [TenantPermission] over the authority replica (INV-Q-10027, INV-Q-17215 A), written like svc-tenancy's
 * `ReplicaTenantAuthority`: true only when `decideTenant` allows the person (kind HUMAN) a TENANT-scope grant of exactly
 * the permission key, answered from the local replica. With no replica bound, or a DEGRADED answer, the person is
 * refused (fail closed).
 */
class ReplicaTenantPermission(
    private val replica: AuthorityReplica?,
    private val tenants: TenantContext,
) : TenantPermission {
    override fun holds(
        person: String,
        permissionKey: String,
    ): Boolean {
        val decision =
            replica?.decideTenant(tenants.current(), Principal(person, PrincipalKind.HUMAN), permissionKey)
                ?: return false
        return decision.allowed && decision.source == DecisionSource.LOCAL
    }
}
