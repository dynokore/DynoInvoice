package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.alert.AccessAlerts
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper

/**
 * The entity an alert event is filed under. AccessAlertRaised is a domain event, and the outbox refuses a domain event
 * under the tenant-wide entity (FR-K.3, INV-Q-3165), so every alert on this channel concerns an entity. ACC-002 is not
 * raised here: break-glass raises it as the platform event BreakGlassAlertRaised (INV-Q-8839 A).
 */
fun interface AlertEntity {
    fun of(scope: String): EntityRef
}

/**
 * TPL-05: raises an ACC alert as the contract event AccessAlertRaised and appends it through outbox-and-queue, in the
 * command transaction the caller holds (CS-17). The payload is the `access-alert-raised-payload` schema of
 * platform-contracts (`accCode`, `scope`); a test validates it against that schema. The emission guard of
 * outbox-and-queue refuses the type unless the event catalogue lists access-svc as its producer.
 */
class AccessAlertOutbox(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper,
    private val entity: AlertEntity,
) : AccessAlerts {
    override fun raise(
        code: String,
        scope: String,
    ) {
        val payload = mapper.createObjectNode()
        payload.put("accCode", code)
        payload.put("scope", scope)
        outbox.append(
            listOf(OutboundEvent(EVENT_TYPE, payload.toString())),
            EventContexts.forAggregate(tenants.current(), entity.of(scope), scope),
        )
    }

    companion object {
        /** The asyncapi channel this class publishes (operations.tsv lists it). */
        const val CHANNEL = "accessAccessAlertRaised"

        /** The name of the channel's message in the asyncapi document. */
        const val EVENT_TYPE = "com.dynokore.gst.access.AccessAlertRaised.v1"
    }
}
