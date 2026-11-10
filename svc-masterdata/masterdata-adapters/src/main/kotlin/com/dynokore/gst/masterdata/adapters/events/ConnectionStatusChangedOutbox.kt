package com.dynokore.gst.masterdata.adapters.events

import com.dynokore.gst.masterdata.application.connection.ConnectionStatusChanged
import com.dynokore.gst.masterdata.application.connection.ConnectionStatusPublisher
import com.dynokore.gst.masterdata.domain.connection.ConnectionState
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * TPL-05: maps a requested state change of a connection to the platform event `ConnectionStatusChanged`
 * (INV-REQ-836 behavior 3) and appends it through outbox-and-queue, inside the caller's transaction. The type is the
 * pinned catalogue's; the envelope carries this instance's `instanceid` (set by the outbox writer). A connection has no
 * entity, so the entity is tenant-wide under the connection's tenant. The payload is the
 * `connection-status-changed-payload` schema: connection, state (lower case: up, degraded, down) and the last failure
 * as a time and a code, never raw error text.
 */
class ConnectionStatusChangedOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
    private val tenants: TenantContext,
) : ConnectionStatusPublisher {
    override fun publish(event: ConnectionStatusChanged) {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        val context =
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef.TENANT_WIDE,
                docUuid = null,
                transactionDate = null,
                aggregateKey = event.connectionRef,
                aggregateVersion = null,
                correlation = CorrelationId(correlation),
                causation = CausationId(causation),
            )
        outbox.append(listOf(OutboundEvent(EVENT_TYPE, payload(event).toString())), context)
    }

    internal fun payload(event: ConnectionStatusChanged): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("connection", event.connectionRef)
        root.put("state", wire(event.state))
        event.lastFailure?.let {
            val failure = root.putObject("lastFailure")
            failure.put("at", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(it.at.atOffset(ZoneOffset.UTC)))
            failure.put("reason", it.failure.detail ?: it.failure.classification.name)
        }
        return root
    }

    /** An exhaustive `when`: a new state cannot go unmapped. */
    private fun wire(state: ConnectionState): String =
        when (state) {
            ConnectionState.UP -> "up"
            ConnectionState.DEGRADED -> "degraded"
            ConnectionState.DOWN -> "down"
        }

    companion object {
        /** The pinned catalogue's type: `com.dynokore.gst.platform.ConnectionStatusChanged.v1`. */
        val EVENT_TYPE: String = PlatformEventCatalogue.CONNECTION_STATUS_CHANGED.eventType
    }
}
