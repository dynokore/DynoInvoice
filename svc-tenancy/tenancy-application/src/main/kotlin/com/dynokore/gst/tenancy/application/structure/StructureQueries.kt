package com.dynokore.gst.tenancy.application.structure

import com.dynokore.gst.tenancy.application.status.EntityStatusStore
import com.dynokore.gst.tenancy.application.tenant.TenantRecord
import com.dynokore.gst.tenancy.application.tenant.TenantStore
import com.dynokore.platform.tenancykernel.TransactionRunner

/** A structure node as getEntityStructure answers it: the node-generic fields and the full node (INV-API-3329). */
data class StructureView(
    val ref: String,
    val name: String,
    val lifecycleState: String,
    val node: StructureNodeState,
)

/** Reads of the structure for one bound tenant. */
class StructureQueries(
    private val tx: TransactionRunner,
    private val store: StructureStore,
    private val tenants: TenantStore,
    private val statuses: EntityStatusStore,
) {
    /** The tenant's own ref, or null when the bound tenant has no row. */
    fun tenantRef(): String? = tx.inTransaction { tenants.current()?.ref }

    /** The node [ref] names (an entity, group, business unit or the tenant), or null when the tenant holds none. */
    fun node(ref: String): StructureView? =
        tx.inTransaction {
            store
                .entity(
                    ref,
                )?.let { StructureView(it.ref, it.legalName, it.lifecycleState, entityNode(it, profileOf(it))) }
                ?: tenantRef()?.let { tenantRef -> store.group(ref)?.let { groupView(it, tenantRef) } }
                ?: store.businessUnit(ref)?.let { StructureView(it.ref, it.name, live(it.retired), unitNode(it)) }
                ?: tenants.current()?.takeIf { it.ref == ref }?.let(::tenantView)
        }

    private fun profileOf(entity: EntityRecord) =
        statuses.history(entity.id).map { ProfileEntry(it.attribute, it.value, it.effectiveFrom) }

    private fun groupView(
        group: GroupRecord,
        tenantRef: String,
    ) = StructureView(group.ref, group.name, live(group.retired), groupNode(group, tenantRef))

    private fun tenantView(tenant: TenantRecord) =
        StructureView(
            tenant.ref,
            tenant.legalName,
            live(tenant.retired),
            StructureNodeState(NodeType.TENANT, null, tenant.retired, tenant.legalName, null, tenant.structureVersion),
        )

    private fun live(retired: Boolean) = if (retired) RETIRED else LIVE

    private companion object {
        const val LIVE = "LIVE"
        const val RETIRED = "RETIRED"
    }
}
