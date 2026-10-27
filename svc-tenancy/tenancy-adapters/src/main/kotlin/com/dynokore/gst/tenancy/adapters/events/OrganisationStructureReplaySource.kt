package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.adapters.persistence.ReplayCursor
import com.dynokore.gst.tenancy.adapters.persistence.ReplayNode
import com.dynokore.gst.tenancy.adapters.persistence.ReplayReader
import com.dynokore.gst.tenancy.application.structure.ChangeType
import com.dynokore.gst.tenancy.application.structure.StructureNodeState
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
 * The producer side of the replay of OrganisationStructureChanged (INV-REQ-1199, INV-Q-9081 A, INV-Q-11719 A): a state
 * snapshot of every group, entity and business unit of the bound tenant, read from the tenancy tables. The tenant row
 * is not replayed (no live event publishes it). Each state carries the node exactly as getEntityStructure answers it,
 * its stored `structureVersion` (no new version is drawn) and no `docSequence`; changeType is RETIRED for a retired
 * node and UPDATED otherwise; previousParentRef is omitted.
 */
class OrganisationStructureReplaySource(
    private val reader: ReplayReader,
    private val mapper: ObjectMapper,
) : ReplayStateSource {
    override val eventType: String = ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED

    override fun tenants(): List<TenantId> = reader.tenantIds()

    override fun page(
        scope: ReplayScope?,
        cursor: String?,
        limit: Int,
    ): ReplayPage {
        require(limit >= 1) { "limit must be at least 1" }
        val rows = reader.structure(scope, cursor?.let(ReplayCursor::decode), limit + 1)
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
        val root = mapper.createObjectNode()
        root.put("nodeType", row.type.name)
        root.put("nodeRef", row.ref)
        root.put("changeType", (if (row.node.retired) ChangeType.RETIRED else ChangeType.UPDATED).name)
        row.node.parentRef?.let { root.put("parentRef", it) }
        putNode(root.putObject("node"), row.node)
        return root
    }

    private fun putNode(
        target: ObjectNode,
        state: StructureNodeState,
    ) {
        target.put("kind", state.kind.name)
        state.parentRef?.let { target.put("parentRef", it) }
        target.put("retired", state.retired)
        target.put("name", state.name)
        state.code?.let { target.put("code", it) }
        state.uen?.let { target.put("uen", it) }
        state.gstn?.let { target.put("gstn", it) }
        val profile = target.putArray("profile")
        state.profile.forEach {
            val entry = profile.addObject()
            entry.put("attribute", it.attribute)
            entry.put("value", it.value)
            entry.put("effectiveDate", it.effectiveDate.toString())
        }
        target.put("structureVersion", state.structureVersion)
    }
}
