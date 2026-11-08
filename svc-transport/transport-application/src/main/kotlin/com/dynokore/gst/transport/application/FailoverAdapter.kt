package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.EntityRef

/** The entity is assigned to an adapter other than the one the failover names as the source. */
class EntityNotOnAdapter(
    entity: EntityRef,
    adapter: AdapterId,
) : IllegalStateException("entity $entity is not assigned to adapter $adapter")

/**
 * `failoverAdapter` (INV-REQ-796): cuts one entity over from [from] to its second adapter [target]. Only the entity's
 * assignment changes: new despatches of the entity use [target], a version in flight stays on the adapter of its
 * first attempt ([SubmitPayload]), and no other entity and no submission state held by the caller is touched. A repeat
 * for an entity already on [target] is a no-op.
 */
class FailoverAdapter(
    private val adapters: AccessPointAdapters,
    private val assignments: EntityAdapterAssignments,
) {
    fun failover(
        entity: EntityRef,
        from: AdapterId,
        target: AdapterId,
    ): RouteOutcome {
        if (from !in adapters.ids()) throw AdapterNotRegistered(from.value)
        if (target !in adapters.ids()) throw AdapterNotRegistered(target.value)
        val current = assignments.adapterFor(entity)
        if (current == target) return RouteOutcome.NO_OP
        if (current != from) throw EntityNotOnAdapter(entity, from)
        assignments.assign(entity, target)
        return RouteOutcome.CHANGED
    }
}
