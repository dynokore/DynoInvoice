package com.dynokore.gst.consoleBff.adapters.authz

import com.dynokore.gst.consoleBff.application.authz.ReadPermissions
import com.dynokore.gst.consoleBff.application.exceptions.EntityScope
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Duration

/**
 * The entities a principal may read exceptions of: the entities the replica holds a grant on now, kept when
 * [AuthorityReplica.decide] allows the permission key of the `getExceptions` route on that entity. The key is read
 * from [CatalogueReadPermissions.ENTRIES], never written again here, and no role name is read (INV-Q-10027). Fail
 * closed (OWASP ASVS V4.1.1): an unmapped route, a malformed tenant id, a denied or degraded decision and a thrown
 * exception all give no entity.
 */
class ReplicaEntityScope(
    private val replica: AuthorityReplica,
    private val permissions: ReadPermissions,
    private val clock: Clock,
    private val budget: Duration,
) : EntityScope {
    override fun entitiesOf(
        tenantId: String,
        principalId: String,
    ): Set<String> {
        val route = CatalogueReadPermissions.ENTRIES.firstOrNull { it.operationId == OPERATION } ?: return emptySet()
        val key = permissions.keyFor(route.method, route.pathTemplate) ?: return emptySet()
        return runCatching {
            val tenant = TenantId.of(tenantId)
            val principal = Principal(principalId, PrincipalKind.HUMAN)
            replica
                .heldEntities(tenant, principalId)
                .filterTo(mutableSetOf()) { entity ->
                    replica.decide(AuthorizationRequest(tenant, principal, entity, key), Deadline.after(budget, clock)).allowed
                }
        }.getOrDefault(emptySet())
    }

    private companion object {
        const val OPERATION = "getExceptions"
    }
}
