package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.adapters.persistence.ReplayCursor
import com.dynokore.gst.tenancy.adapters.persistence.ReplayNode
import com.dynokore.gst.tenancy.adapters.persistence.ReplayReader
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.ReplayPage
import com.dynokore.platform.outboxandqueue.ReplayScope
import com.dynokore.platform.outboxandqueue.ReplayStateSource
import com.dynokore.platform.outboxandqueue.ReplayedState
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * The producer side of the replay of EntityProfileChanged (INV-REQ-1199, INV-Q-9081 A, INV-Q-11719 A): one state per
 * entity of the bound tenant that has at least one status entry. `changedAttributes` lists every profile entry of the
 * node in order (attribute and newValue, no oldValue); `effectiveDate` is the effective_from of the entry with the
 * greatest recorded_at. The node, the stored `structureVersion` and the absent `docSequence` are as for
 * [OrganisationStructureReplaySource].
 */
class EntityProfileReplaySource(
    private val reader: ReplayReader,
    private val mapper: ObjectMapper,
) : ReplayStateSource {
    override val eventType: String = ProductEventTypes.ENTITY_PROFILE_CHANGED

    override fun tenants(): List<TenantId> = reader.tenantIds()

    override fun page(
        scope: ReplayScope?,
        cursor: String?,
        limit: Int,
    ): ReplayPage {
        require(limit >= 1) { "limit must be at least 1" }
        val rows = reader.profiles(scope, cursor?.let(ReplayCursor::decode), limit + 1)
        val items = rows.take(limit)
        val next = if (rows.size > limit) ReplayCursor(items.last().type, items.last().ref).encode() else null
        return ReplayPage(items.map(::state), next)
    }

    internal fun state(row: ReplayNode): ReplayedState =
        ReplayedState(
            type = eventType,
            dataJson = payload(row).toString(),
            entity = EntityRef(row.entityRef),
            aggregateKey = row.ref,
            docSequence = null,
        )

    internal fun payload(row: ReplayNode): ObjectNode {
        val state = row.node
        val root = mapper.createObjectNode()
        root.put("entityRef", row.ref)
        val attributes = root.putArray("changedAttributes")
        state.profile.forEach {
            val item = attributes.addObject()
            item.put("attribute", it.attribute)
            item.put("newValue", it.value)
        }
        root.put("effectiveDate", checkNotNull(row.profileEffectiveDate) { "a profile state has an entry" }.toString())
        val node = root.putObject("node")
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
}
