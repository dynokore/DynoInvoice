package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.breakglass.BreakGlassSession
import com.dynokore.gst.access.application.breakglass.BreakGlassSessionEvents
import com.dynokore.gst.access.application.breakglass.BreakGlassSessionStatus
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper

/**
 * TPL-05: announces a break-glass session change as the platform event `BreakGlassSessionChanged` (INV-Q-9119 A,
 * INV-AC-9303) and appends it through outbox-and-queue in the command transaction the caller holds (CS-17). The event
 * carries the real tenant and the tenant-wide entity. The payload is the `break-glass-session-changed-payload` schema
 * of platform-contracts (`unlockRef`, `tenantRef`, `status`, `expiresAt`, `approvedBy`).
 */
class BreakGlassSessionChangedOutbox(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper,
) : BreakGlassSessionEvents {
    override fun changed(
        session: BreakGlassSession,
        status: BreakGlassSessionStatus,
    ) {
        val payload = mapper.createObjectNode()
        payload.put("unlockRef", session.ref)
        payload.put("tenantRef", session.tenantRef)
        payload.put("status", status.name)
        payload.put("expiresAt", session.expiresAt.toString())
        val approvers = payload.putArray("approvedBy")
        session.approvedBy.forEach { approvers.add(it) }
        outbox.append(
            listOf(OutboundEvent(EVENT_TYPE, payload.toString())),
            EventContexts.forAggregate(tenants.current(), EntityRef.TENANT_WIDE, "break-glass:${session.ref}"),
        )
    }

    companion object {
        /** The platform event this class publishes. */
        const val EVENT_TYPE = "com.dynokore.gst.platform.BreakGlassSessionChanged.v1"
    }
}
