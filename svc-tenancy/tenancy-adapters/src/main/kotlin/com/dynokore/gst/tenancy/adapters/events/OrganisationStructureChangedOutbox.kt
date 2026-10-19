package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.structure.StructureChange
import com.dynokore.gst.tenancy.application.structure.StructureChangePublisher
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * TPL-05: maps a structure change to the contract event OrganisationStructureChanged (INV-API-3327) and appends it
 * through outbox-and-queue, in the command transaction the caller holds (CS-17). The payload is the
 * `organisation-structure-changed-payload` schema of platform-contracts; the payload-schema test class of the
 * channel validates it against that schema. The emission guard refuses the type unless the catalogue lists
 * tenancy-svc as its producer.
 */
class OrganisationStructureChangedOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
) : StructureChangePublisher {
    override fun publish(change: StructureChange) {
        val context =
            EventContexts.forAggregate(
                TenantId.of(change.tenant.toString()),
                EntityRef(change.entityRef),
                change.nodeRef,
            )
        outbox.append(
            listOf(OutboundEvent(ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED, payload(change).toString())),
            context,
        )
    }

    internal fun payload(change: StructureChange): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("nodeType", change.nodeType.name)
        root.put("nodeRef", change.nodeRef)
        root.put("changeType", change.changeType.name)
        change.parentRef?.let { root.put("parentRef", it) }
        change.previousParentRef?.let { root.put("previousParentRef", it) }
        val node = root.putObject("node")
        node.put("kind", change.node.kind.name)
        change.node.parentRef?.let { node.put("parentRef", it) }
        node.put("retired", change.node.retired)
        node.put("name", change.node.name)
        change.node.code?.let { node.put("code", it) }
        node.putArray("profile")
        node.put("structureVersion", change.node.structureVersion)
        return root
    }

    companion object {
        /** The asyncapi channel this class publishes (operations.tsv lists it by its message name). */
        const val CHANNEL = "OrganisationStructureChanged"
    }
}
