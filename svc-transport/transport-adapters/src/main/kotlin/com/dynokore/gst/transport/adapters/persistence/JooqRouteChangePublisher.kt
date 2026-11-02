package com.dynokore.gst.transport.adapters.persistence

import com.dynokore.gst.transport.application.RouteChangePublisher
import com.dynokore.gst.transport.domain.AcknowledgementRoute
import com.dynokore.gst.transport.domain.TenantRef
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import com.dynokore.gst.transport.domain.EntityRef as AssignedEntityRef

/**
 * Writes the `AccessPointRouteChanged` outbox row (INV-REQ-1310; owner answers INV-Q-4872 and INV-Q-4993) in the open
 * transaction of the caller, which is the one unit of work of the route store and the event (`RouteChangeRecorder`).
 *
 * The row's entity is [EntityRef.TENANT_WIDE] with the bound tenant, for every change including one whose entity refs
 * are empty (ruling INV-Q-15474 A): never the connection ref, an entity ref of the list or the tenant id. The refs of
 * the list travel in the payload (`entityRefs`); the payload leaves `acknowledgementRoute` out when the route is
 * cleared.
 */
class JooqRouteChangePublisher(
    private val outbox: OutboxWriter,
) : RouteChangePublisher {
    private val mapper = JsonMapper.builder().build()

    override fun publish(
        tenant: TenantRef,
        connectionRef: String,
        route: AcknowledgementRoute?,
        entityRefs: List<AssignedEntityRef>,
        version: Long,
    ) {
        val payload = linkedMapOf<String, Any>("connectionRef" to connectionRef)
        if (route != null) payload["acknowledgementRoute"] = route.name
        payload["entityRefs"] = entityRefs.map { it.value }
        payload["version"] = version
        val correlation = "route:$connectionRef:$version"
        outbox.append(
            listOf(OutboundEvent(EVENT_TYPE, mapper.writeValueAsString(payload))),
            EventContext(
                TenantId(UUID.fromString(tenant.value)),
                EntityRef.TENANT_WIDE,
                null,
                null,
                connectionRef,
                null,
                CorrelationId(correlation),
                CausationId(correlation),
            ),
        )
    }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.transport.AccessPointRouteChanged.v1"
    }
}
