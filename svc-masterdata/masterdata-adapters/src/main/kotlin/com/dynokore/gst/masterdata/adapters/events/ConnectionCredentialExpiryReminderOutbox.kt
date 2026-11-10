package com.dynokore.gst.masterdata.adapters.events

import com.dynokore.gst.masterdata.application.connection.ConnectionCredentialExpiryReminderRequested
import com.dynokore.gst.masterdata.application.connection.CredentialExpiryReminderPublisher
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
 * TPL-05: maps a requested expiry reminder to the platform event `ConnectionCredentialExpiryReminderRequested`
 * (INV-REQ-837) and appends it through outbox-and-queue, inside the check's transaction. The type is the pinned
 * catalogue's. A connection has no entity, so the entity is tenant-wide under the connection's tenant. The payload is
 * the `connection-credential-expiry-reminder-requested-payload` schema: connection, expiry instant and offset in days;
 * it carries no credential reference, person or address (the recipients are routing data of the sender).
 */
class ConnectionCredentialExpiryReminderOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
    private val tenants: TenantContext,
) : CredentialExpiryReminderPublisher {
    override fun publish(event: ConnectionCredentialExpiryReminderRequested) {
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

    internal fun payload(event: ConnectionCredentialExpiryReminderRequested): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("connection", event.connectionRef)
        root.put(
            "expiresAt",
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(event.credentialExpiresAt.atOffset(ZoneOffset.UTC)),
        )
        root.put("offsetDays", event.offsetDays)
        return root
    }

    companion object {
        /** The pinned catalogue's type: `com.dynokore.gst.platform.ConnectionCredentialExpiryReminderRequested.v1`. */
        val EVENT_TYPE: String = PlatformEventCatalogue.CONNECTION_CREDENTIAL_EXPIRY_REMINDER_REQUESTED.eventType
    }
}
