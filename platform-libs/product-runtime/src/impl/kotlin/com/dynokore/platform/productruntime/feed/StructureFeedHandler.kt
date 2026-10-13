package com.dynokore.platform.productruntime.feed

import com.dynokore.gst.productRuntimeProfileEvents.adapters.events.model.EntityProfileChangedPayload
import com.dynokore.gst.productRuntimeProfileEvents.adapters.events.model.EntityProfileChangedPayloadNode
import com.dynokore.gst.productRuntimeStructureEvents.adapters.events.model.OrganisationStructureChangedPayload
import com.dynokore.gst.productRuntimeStructureEvents.adapters.events.model.OrganisationStructureChangedPayloadNode
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.serviceruntime.replica.EntityProfileChanged
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.ProfileEntry
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureEvent
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica

/**
 * Feeds `OrganisationStructureChanged` and `EntityProfileChanged` into the structure replica (INV-REQ-1200 b1, AD-26),
 * decoded from the generated tenancy payload types (INV-Q-3852). Each event carries the node's full state after the
 * change with tenancy-svc's structure version, which the replica orders by and replaces whole (INV-Q-3324,
 * INV-Q-3259).
 *
 * An event without `node` (the 1.0 shape, which only a producer defect can now emit; INV-T-3298) is a
 * [ContractViolation]: it goes to dead-letter triage with its reason, the consumer continues with the next record,
 * and the replica keeps serving the node it holds (coordinator ruling INV-Q-3850; ARC 4.2 step 5, INV-REQ-1200 b3).
 */
class StructureFeedHandler(
    private val replica: StructureReplica,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        replica.accept(decode(event))
        return HandleOutcome.APPLIED
    }

    /** The service-runtime event for [event]; throws [ContractViolation] when it does not meet the contract. */
    fun decode(event: Envelope): StructureEvent =
        when (event.type) {
            ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED -> {
                val payload = ContractJson.read(event.dataJson, OrganisationStructureChangedPayload::class.java)
                val node = payload.node ?: throw noNode(event, payload.nodeRef)
                OrganisationStructureChanged(
                    tenant = event.tenant,
                    nodeRef = payload.nodeRef,
                    change = StructureChange.of(payload.changeType.value),
                    node = node.toReplicaNode(),
                )
            }

            ProductEventTypes.ENTITY_PROFILE_CHANGED -> {
                val payload = ContractJson.read(event.dataJson, EntityProfileChangedPayload::class.java)
                val node = payload.node ?: throw noNode(event, payload.entityRef)
                EntityProfileChanged(tenant = event.tenant, entityRef = payload.entityRef, node = node.toReplicaNode())
            }

            else -> {
                throw ContractViolation("the structure feed takes no ${event.type}")
            }
        }

    private fun noNode(
        event: Envelope,
        ref: String,
    ) = ContractViolation(
        "${event.type} ${event.id} for $ref carries no node: the structure replica keeps the node it holds " +
            "(INV-Q-3850, INV-T-3298)",
    )

    private fun OrganisationStructureChangedPayloadNode.toReplicaNode() =
        StructureNode(
            kind = StructureNodeKind.of(kind.value),
            parentRef = parentRef,
            retired = retired,
            name = name,
            code = code,
            profile = profile.map { ProfileEntry(it.attribute, it.`value`, it.effectiveDate) },
            structureVersion = structureVersion.toLong(),
        )

    private fun EntityProfileChangedPayloadNode.toReplicaNode() =
        StructureNode(
            kind = StructureNodeKind.of(kind.value),
            parentRef = parentRef,
            retired = retired,
            name = name,
            code = code,
            profile = profile.map { ProfileEntry(it.attribute, it.`value`, it.effectiveDate) },
            structureVersion = structureVersion.toLong(),
        )
}
