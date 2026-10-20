package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.tenancykernel.BoundEntitySource
import com.dynokore.platform.tenancykernel.TenantId
import java.util.UUID

/**
 * The entity set a transaction binds, from the [AuthorityReplica] (INV-REQ-866, INV-AC-1912; INV-T-7520, INV-Q-7453):
 * the entities on which the actor holds a grant in the tenant ([AuthorityReplica.heldEntities]; TENANT and GROUP grants
 * arrive expanded to one entity, and an `UNKNOWN` scope level counts for none). An actor with no grant, a replica that
 * holds nothing, and an entity reference that is not a canonical UUID give no entity: the set is empty, never the full
 * tenant. Application code does not set the entity set; tenancy-kernel signs what this returns.
 */
class ReplicaBoundEntities(
    private val replica: AuthorityReplica,
) : BoundEntitySource {
    override fun entitiesOf(
        tenant: TenantId,
        actor: String,
    ): Set<UUID> =
        replica
            .heldEntities(tenant, actor)
            .mapNotNullTo(mutableSetOf()) { ref ->
                runCatching { UUID.fromString(ref) }.getOrNull()?.takeIf { it.toString() == ref }
            }
}
