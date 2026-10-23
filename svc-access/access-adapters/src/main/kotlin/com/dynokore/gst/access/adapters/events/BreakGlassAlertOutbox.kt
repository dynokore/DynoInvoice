package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.breakglass.BreakGlassAlerts
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper

/**
 * TPL-05: raises ACC-002 as the platform event `BreakGlassAlertRaised` (INV-REQ-897 behaviour 1, INV-Q-8839 A) and
 * appends it through outbox-and-queue in the command transaction the caller holds (CS-17). The event is of the platform
 * class: it carries the real tenant and the tenant-wide entity, which the envelope omits when written. The payload is
 * the `break-glass-alert-raised-payload` schema of platform-contracts (`accCode`, `scope`, `action`, `unlockRef`).
 */
class BreakGlassAlertOutbox(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper,
) : BreakGlassAlerts {
    override fun raise(
        code: String,
        action: String,
        unlockRef: String,
    ) {
        val payload = mapper.createObjectNode()
        payload.put("accCode", code)
        payload.put("scope", SCOPE)
        payload.put("action", action)
        payload.put("unlockRef", unlockRef)
        outbox.append(
            listOf(OutboundEvent(EVENT_TYPE, payload.toString())),
            EventContexts.forAggregate(tenants.current(), EntityRef.TENANT_WIDE, "break-glass:$unlockRef"),
        )
    }

    companion object {
        /** The platform event this class publishes (INV-REQ-1226's platform class). */
        const val EVENT_TYPE = "com.dynokore.gst.platform.BreakGlassAlertRaised.v1"

        /** The sealed account is per tenant; every session of this service is bound to a tenant. */
        const val SCOPE = "TENANT"
    }
}
