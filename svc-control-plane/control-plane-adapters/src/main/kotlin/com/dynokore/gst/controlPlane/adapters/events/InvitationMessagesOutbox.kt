package com.dynokore.gst.controlPlane.adapters.events

import com.dynokore.gst.controlPlane.application.commercial.InvitationMessages
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.json.JsonMapper

/**
 * The invitation MessageRequested of the first `tenant.admin` (INV-Q-8677 A), written to the outbox in the open
 * transaction of provisionTenant: one recipient, channel EMAIL, the constant template and the signed invitation
 * reference as link reference. It holds no invoice content.
 */
class InvitationMessagesOutbox(
    private val outbox: OutboxWriter,
) : InvitationMessages {
    private val json = JsonMapper.builder().build()

    override fun request(
        contractReference: CommercialRef,
        firstAdministratorEmail: String,
        invitationRef: String,
        request: RequestContext,
    ) {
        val data =
            linkedMapOf(
                "template" to TEMPLATE,
                "recipients" to listOf(firstAdministratorEmail),
                "channel" to "EMAIL",
                "linkReference" to invitationRef,
            )
        outbox.append(
            listOf(OutboundEvent(ControlPlaneEventCatalogue.MESSAGE_REQUESTED, json.writeValueAsString(data))),
            EventContext(
                tenant = TenantId.PLATFORM,
                entity = EntityRef.PLATFORM,
                docUuid = null,
                transactionDate = null,
                aggregateKey = contractReference.value,
                aggregateVersion = null,
                correlation = CorrelationId(request.correlationId),
                causation = CausationId(request.causationId),
            ),
        )
    }

    companion object {
        /** This service's own constant; the notification template list takes the name from it (INV-Q-8677 A). */
        const val TEMPLATE = "first-administrator-invitation"
    }
}
