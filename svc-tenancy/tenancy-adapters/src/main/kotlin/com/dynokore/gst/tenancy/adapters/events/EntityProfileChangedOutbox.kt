package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.status.EntityProfileChange
import com.dynokore.gst.tenancy.application.status.EntityProfileChangePublisher
import com.dynokore.gst.tenancy.application.structure.NodeType
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * TPL-05: maps an entity profile change to the contract event EntityProfileChanged and appends it through
 * outbox-and-queue, in the command transaction the caller holds (CS-17). The payload is the
 * `entity-profile-changed-payload` schema of platform-contracts: `entityRef`, the optional `entityId` (ENTITY nodes
 * only), `changedAttributes`, `effectiveDate` and the node's full state with its profile entries.
 */
class EntityProfileChangedOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
    /** The id of the `tenancy.entity` row of (tenant, entityRef), or null; the default finds none (no member). */
    private val entityIds: (UUID, String) -> UUID? = { _, _ -> null },
) : EntityProfileChangePublisher {
    override fun publish(change: EntityProfileChange) {
        val context =
            EventContexts.forAggregate(
                TenantId.of(change.tenant.toString()),
                EntityRef(change.entityRef),
                change.entityRef,
            )
        outbox.append(
            listOf(OutboundEvent(ProductEventTypes.ENTITY_PROFILE_CHANGED, payload(change).toString())),
            context,
        )
    }

    internal fun payload(change: EntityProfileChange): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("entityRef", change.entityRef)
        if (change.node.kind == NodeType.ENTITY) {
            entityIds(change.tenant, change.entityRef)?.let { root.put("entityId", it.toString()) }
        }
        val attributes = root.putArray("changedAttributes")
        change.changedAttributes.forEach {
            val item = attributes.addObject()
            item.put("attribute", it.attribute)
            it.oldValue?.let { old -> item.put("oldValue", old) }
            item.put("newValue", it.newValue)
        }
        root.put("effectiveDate", change.effectiveDate.toString())
        change.node.constitution?.let { root.put("constitution", it) }
        val node = root.putObject("node")
        val state = change.node
        node.put("kind", state.kind.name)
        state.parentRef?.let { node.put("parentRef", it) }
        node.put("retired", state.retired)
        node.put("name", state.name)
        state.code?.let { node.put("code", it) }
        state.uen?.let { node.put("uen", it) }
        state.gstn?.let { node.put("gstn", it) }
        val profile = node.putArray("profile")
        state.profile.forEach {
            val entry = profile.addObject()
            entry.put("attribute", it.attribute)
            entry.put("value", it.value)
            entry.put("effectiveDate", it.effectiveDate.toString())
        }
        node.put("structureVersion", state.structureVersion)
        return root
    }

    companion object {
        /** The asyncapi channel this class publishes (operations.tsv lists it by its message name). */
        const val CHANNEL = "EntityProfileChanged"
    }
}
