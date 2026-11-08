package com.dynokore.gst.access.adapters.structure

import com.dynokore.gst.access.application.token.TenantEnvironment
import com.dynokore.gst.access.application.token.TenantEnvironments
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.serviceruntime.replica.StructureEnvironment
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId

/**
 * The environment of a tenant from the TENANT node the structure replica holds for it (INV-Q-9032 A). It reads one
 * tenant's partition only and compares no `nodeRef` with the tenant id (the `nodeRef` of a TENANT node is the tenant
 * record's `ref`). It makes no call to tenancy-svc (INV-REQ-1200). Fail closed (OWASP ASVS V4, NIST SP 800-53 AC-3):
 * no node, a retired node, an unknown or absent environment, or a tenant id that is not a UUID gives null; two or more
 * TENANT nodes in the partition throw, and none is picked.
 */
class ReplicaTenantEnvironments(
    private val structure: StructureReplica,
) : TenantEnvironments {
    override fun of(tenant: TenantRef): TenantEnvironment? {
        val id = runCatching { TenantId.of(tenant.value) }.getOrNull()
        val node = id?.let { soleTenantNode(it) }?.takeUnless { it.retired }
        return when (node?.environment) {
            StructureEnvironment.SANDBOX -> TenantEnvironment.SANDBOX
            StructureEnvironment.PRODUCTION -> TenantEnvironment.PRODUCTION
            StructureEnvironment.UNKNOWN, null -> null
        }
    }

    private fun soleTenantNode(id: TenantId): StructureNode? {
        val nodes =
            structure.replica
                .heldIn(id)
                .values
                .filter { it.kind == StructureNodeKind.TENANT }
        check(nodes.size <= 1) { "the structure replica holds ${nodes.size} TENANT nodes for one tenant" }
        return nodes.singleOrNull()
    }
}
