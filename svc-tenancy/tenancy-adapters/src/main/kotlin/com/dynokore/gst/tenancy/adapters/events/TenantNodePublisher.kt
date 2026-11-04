package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.structure.NodeType
import com.dynokore.gst.tenancy.application.structure.StructureChange
import com.dynokore.gst.tenancy.application.structure.StructureChangePublisher
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.ZoneOffset

/**
 * Publishes the tenant's own TENANT node of OrganisationStructureChanged through the definer function
 * `tenancy.publish_tenant_node` (INV-Q-16720 A, INV-AC-16723): the outbox policy lets the runtime login insert only for
 * the bound tenant, and a tenant is created under the platform binding or its production tenant's. The row is staged
 * by [OutboxWriter.stage] and the function checks it against the stored tenant row and inserts it in the caller's
 * transaction. An exception of the function propagates and the transaction rolls back; the application passes only rows
 * it has just stored. Every other node type goes to [delegate], the `append` path.
 */
class TenantNodePublisher(
    private val outbox: OutboxWriter,
    private val delegate: OrganisationStructureChangedOutbox,
    private val dsl: DSLContext,
    private val mapper: ObjectMapper,
) : StructureChangePublisher {
    override fun publish(change: StructureChange) {
        if (change.nodeType != NodeType.TENANT) {
            delegate.publish(change)
            return
        }
        dsl
            .select(
                DSL.function(
                    DSL.name("tenancy", "publish_tenant_node"),
                    SQLDataType.OTHER,
                    DSL.`val`(stagedRow(change).toString()).cast(SQLDataType.JSONB),
                ),
            ).execute()
    }

    /** The outbox row [OutboxWriter.stage] builds for the TENANT change, with the members the writer inserts. */
    internal fun stagedRow(change: StructureChange): ObjectNode {
        val context =
            EventContexts.forAggregate(
                TenantId.of(change.tenant.toString()),
                EntityRef(change.entityRef),
                change.nodeRef,
            )
        val staged =
            outbox
                .stage(
                    listOf(
                        OutboundEvent(
                            ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED,
                            delegate.payload(change).toString(),
                        ),
                    ),
                    context,
                ).single()
        val envelope = staged.envelope
        val row = mapper.createObjectNode()
        row.put("id", envelope.id.value)
        row.put("topic", staged.topic)
        row.put("type", envelope.type)
        row.put("source", envelope.source)
        row.put("subject", envelope.subject)
        row.put("event_time", envelope.time.atOffset(ZoneOffset.UTC).toString())
        row.put("tenant_id", envelope.tenant.value.toString())
        row.put("entity_ref", envelope.entity.value)
        row.put("instance_id", envelope.instanceId)
        row.put("correlation_id", envelope.correlation.value)
        row.put("causation_id", envelope.causation.value)
        row.set("data", mapper.readTree(envelope.dataJson))
        row.put("testmode", envelope.testMode)
        return row
    }
}
