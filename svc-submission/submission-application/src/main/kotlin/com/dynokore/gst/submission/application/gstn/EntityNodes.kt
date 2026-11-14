package com.dynokore.gst.submission.application.gstn

import com.dynokore.platform.tenancykernel.TenantId

/**
 * A structure node of an entity or group kept from the tenancy structure events (INV-REQ-978, INV-T-22285). Only
 * kind, parent, GSTN and version are kept (data minimisation).
 */
data class EntityNode(
    val nodeRef: String,
    val kind: String,
    val parentRef: String?,
    val gstn: String?,
    val structureVersion: Long,
) {
    init {
        require(nodeRef.isNotBlank()) { "nodeRef is blank" }
        require(kind == KIND_ENTITY || kind == KIND_GROUP) { "kind is neither ENTITY nor GROUP" }
        require(structureVersion >= 1) { "structureVersion is below 1" }
        require(gstn == null || gstn.isNotBlank()) { "gstn is blank" }
    }

    companion object {
        const val KIND_ENTITY = "ENTITY"
        const val KIND_GROUP = "GROUP"
    }
}

/** The locally owned read model of the structure nodes, per tenant. */
interface EntityNodes {
    /** True only when no node is stored for the tenant and reference, or [node] has a strictly higher version. */
    fun record(
        tenant: TenantId,
        node: EntityNode,
    ): Boolean

    /** The node of that tenant only. */
    fun find(
        tenant: TenantId,
        nodeRef: String,
    ): EntityNode?
}

/** The GSTN of an entity for the export header. */
fun interface EntityGstns {
    fun gstnOf(
        tenant: TenantId,
        entityRef: String,
    ): String?
}

/** Own GSTN of an ENTITY node, else the GSTN of its parent GROUP, else null (INV-NOTE-2 section 5.1). */
class StoredEntityGstns(
    private val nodes: EntityNodes,
) : EntityGstns {
    override fun gstnOf(
        tenant: TenantId,
        entityRef: String,
    ): String? {
        val node = nodes.find(tenant, entityRef)?.takeIf { it.kind == EntityNode.KIND_ENTITY } ?: return null
        return node.gstn?.takeIf { it.isNotBlank() } ?: parentGroupGstn(tenant, node.parentRef)
    }

    private fun parentGroupGstn(
        tenant: TenantId,
        parentRef: String?,
    ): String? =
        parentRef
            ?.let { nodes.find(tenant, it) }
            ?.takeIf { it.kind == EntityNode.KIND_GROUP }
            ?.gstn
            ?.takeIf { it.isNotBlank() }
}
