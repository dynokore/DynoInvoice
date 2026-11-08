package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.ConnectionRouteState
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TenantRef

/** An entity is assigned to at most one connection of its tenant (SUB-018). */
class EntityAssignedToOtherConnection(
    val entity: EntityRef,
    val otherConnection: String,
) : IllegalStateException("SUB-018: entity $entity is already assigned to connection $otherConnection")

/** The list names an entity twice: a validation error before anything is read. */
class DuplicateEntityRef(
    val entity: String,
) : IllegalArgumentException("entity ref $entity is repeated")

/** Which other connection of the tenant holds an entity, for SUB-018. */
interface ConnectionAssignments {
    fun otherConnectionHolding(
        tenant: TenantRef,
        entity: EntityRef,
        connectionRef: String,
    ): String?
}

/**
 * `setConnectionEntities` (INV-T-4913, owner answer INV-Q-4993 B): replaces the entity refs of a connection whole and
 * publishes through the one publishing function of INV-T-4982 (`AccessPointRouteChanged`, version one higher). Every
 * refusal is raised before anything is stored or published: a repeated ref, an entity held by another connection of
 * the tenant (SUB-018), the removal of an entity the Observe guard blocks (SUB-019).
 */
class SetConnectionEntities(
    private val recorder: RouteChangeRecorder,
    private val assignments: ConnectionAssignments,
    private val removal: EntityRemovalGuard,
) {
    fun set(
        tenant: TenantRef,
        connectionRef: String,
        entityRefs: List<String>,
    ): RouteOutcome {
        entityRefs
            .groupBy { it }
            .entries
            .firstOrNull { it.value.size > 1 }
            ?.let { throw DuplicateEntityRef(it.key) }
        val wanted = entityRefs.map(::EntityRef)
        val state: ConnectionRouteState = recorder.load(tenant, connectionRef)
        wanted.forEach { e ->
            assignments.otherConnectionHolding(tenant, e, connectionRef)?.let {
                throw EntityAssignedToOtherConnection(e, it)
            }
        }
        state.entityRefs.filter { it !in wanted }.forEach { removal.check(tenant, connectionRef, it) }
        return recorder.record(tenant, connectionRef, state.withEntityRefs(wanted))
    }
}
