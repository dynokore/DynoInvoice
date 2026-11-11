package com.dynokore.gst.access.adapters.structure

import com.dynokore.gst.access.application.operatorsession.TenantDirectory
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId

/**
 * INV-Q-16471 A: a tenant exists when, after `TenantId.fromClaim`, the structure replica holds a node of kind TENANT
 * for it that is not retired. It reads what the replica holds and makes no call to tenancy-svc. A reference that does
 * not parse, the platform id, no held node, a node of another kind and a retired tenant all answer null.
 */
class ReplicaTenantDirectory(
    private val structure: StructureReplica,
) : TenantDirectory {
    override fun live(tenantRef: String): TenantId? {
        val tenant = runCatching { TenantId.fromClaim(tenantRef) }.getOrNull() ?: return null
        val nodes =
            structure.replica.heldWhere { it.tenant == tenant }.values.filter {
                it.kind ==
                    StructureNodeKind.TENANT
            }
        return if (nodes.isNotEmpty() && nodes.none { node -> node.retired }) tenant else null
    }
}
