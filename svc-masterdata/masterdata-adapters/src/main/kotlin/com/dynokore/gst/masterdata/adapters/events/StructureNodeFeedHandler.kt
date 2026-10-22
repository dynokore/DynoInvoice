package com.dynokore.gst.masterdata.adapters.events

import com.dynokore.gst.masterdata.application.counterparty.ApplyStructureNode
import com.dynokore.gst.masterdata.domain.counterparty.StructureNode
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.productruntime.ProductEventTypes
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * INV-Q-8427 A: applies the `node` of EntityProfileChanged and OrganisationStructureChanged to this service's own
 * structure table (the contract payload of tenancy-svc carries kind, parentRef, retired, uen, gstn and structureVersion
 * in `node`). A state with a structure version at or below the one held is ignored (INV-Q-3324). An event without a
 * `node` is a contract violation and goes to dead-letter triage (INV-Q-3850). The platform's structure replica is fed
 * separately by product-runtime and does not hold uen or gstn.
 */
class StructureNodeFeedHandler(
    private val apply: ApplyStructureNode,
    private val mapper: ObjectMapper,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val stored = apply.handle(decode(event))
        return if (stored) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }

    /** The node of [event]; a [ContractViolation] when the event is not one of the two or lacks what the node needs. */
    internal fun decode(event: Envelope): StructureNode {
        val payload = mapper.readTree(event.dataJson)
        val ref =
            when (event.type) {
                ProductEventTypes.ENTITY_PROFILE_CHANGED -> payload.text("entityRef")
                ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED -> payload.text("nodeRef")
                else -> violation("the structure feed of masterdata-svc takes no ${event.type}")
            } ?: violation("${event.type} ${event.id} carries no node reference")
        val node =
            payload.get("node")?.takeIf { !it.isNull } ?: violation("${event.type} ${event.id} for $ref has no node")
        return StructureNode(
            nodeRef = ref,
            kind = node.text("kind") ?: violation("${event.type} ${event.id} node has no kind"),
            parentRef = node.text("parentRef"),
            retired =
                node.get("retired")?.takeIf { it.isBoolean }?.asBoolean() ?: violation("node of $ref has no retired"),
            uen = node.text("uen"),
            gstn = node.text("gstn"),
            structureVersion =
                node.get("structureVersion")?.takeIf { it.canConvertToLong() }?.asLong()
                    ?: violation("node of $ref has no structureVersion"),
        )
    }

    private fun violation(message: String): Nothing = throw ContractViolation(message)

    private fun JsonNode.text(field: String): String? =
        get(field)?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
}
