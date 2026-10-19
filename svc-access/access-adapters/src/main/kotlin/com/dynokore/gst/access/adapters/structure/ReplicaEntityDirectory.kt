package com.dynokore.gst.access.adapters.structure

import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.domain.grant.EntityMembership
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.GroupRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.serviceruntime.replica.StructureKey
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * The structure of the bound tenant from service-runtime's structure replica (INV-REQ-1213 behaviour 1: structure is
 * served from cache, fed by OrganisationStructureChanged and EntityProfileChanged; changes are announced by event). It
 * reads what the replica holds and makes no call to tenancy-svc. A retired or unknown entity has no membership.
 */
class ReplicaEntityDirectory(
    private val structure: StructureReplica,
    private val tenants: TenantContext,
) : EntityDirectory {
    override fun membership(entity: EntityRef): EntityMembership? {
        val tenant = tenants.current()
        val node =
            structure.replica.peek(StructureKey(tenant, entity.value))?.takeIf { it.isLiveEntity() } ?: return null
        val group =
            node.parentRef
                ?.let { structure.replica.peek(StructureKey(tenant, it)) }
                ?.takeIf { it.kind == StructureNodeKind.GROUP && !it.retired }
                ?.let { GroupRef(node.parentRef!!) }
        return EntityMembership(TenantRef(tenant.toString()), group)
    }

    override fun entitiesCoveredBy(scope: Scope): Set<EntityRef> {
        val tenant = tenants.current()
        val live = structure.replica.heldWhere { it.tenant == tenant }.filterValues { it.isLiveEntity() }
        return when (scope) {
            is Scope.Tenant -> {
                live.keys.map { EntityRef(it.nodeRef) }.toSet()
            }

            is Scope.Group -> {
                live
                    .filterValues { it.parentRef == scope.group.value }
                    .keys
                    .map {
                        EntityRef(
                            it.nodeRef,
                        )
                    }.toSet()
            }

            is Scope.Entity -> {
                setOf(scope.entity)
            }

            is Scope.EntityPeriod -> {
                setOf(scope.entity)
            }

            is Scope.ConsentDerived -> {
                emptySet()
            } // client entities come from the consent records (INV-T-4939)
        }
    }

    private fun StructureNode.isLiveEntity(): Boolean = kind == StructureNodeKind.ENTITY && !retired
}
