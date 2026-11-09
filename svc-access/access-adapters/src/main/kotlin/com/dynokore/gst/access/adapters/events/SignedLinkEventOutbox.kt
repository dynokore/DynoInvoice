package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.link.LinkInvalidationReason
import com.dynokore.gst.access.application.link.LinkInvalidations
import com.dynokore.gst.access.domain.link.SignedLink
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper

/**
 * TPL-05: appends `SignedLinkIssued` and `SignedLinkInvalidated` through outbox-and-queue, in the transaction the
 * caller holds (CS-17), so a rolled-back transaction publishes nothing. The payloads are the
 * `signed-link-issued-payload` and `signed-link-invalidated-payload` schemas of platform-contracts: the link reference,
 * the request the link was issued for, and the facts of the state change. Never a passcode, token, e-mail address or
 * mobile number (OWASP ASVS V3). Both events are filed under the alert entity of the link.
 */
class SignedLinkEventOutbox(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper,
) : LinkInvalidations {
    /** Appends `SignedLinkIssued` for the stored [link]. */
    fun issued(link: SignedLink) {
        val payload = mapper.createObjectNode()
        payload.put("linkRef", link.ref.value)
        payload.put("subjectReference", subjectOf(link))
        payload.put("purpose", link.purpose)
        payload.put("tenant", tenants.current().value.toString())
        payload.put("expiry", link.expiresAt.toString())
        append(ISSUED_TYPE, payload.toString(), link)
    }

    override fun invalidated(
        link: SignedLink,
        reason: LinkInvalidationReason,
    ) {
        val payload = mapper.createObjectNode()
        payload.put("linkRef", link.ref.value)
        payload.put("subjectReference", subjectOf(link))
        payload.put("reason", reason.name)
        payload.put("invalidatedAt", checkNotNull(link.invalidatedAt) { "an invalidated link has its time" }.toString())
        append(INVALIDATED_TYPE, payload.toString(), link)
    }

    private fun subjectOf(link: SignedLink): String =
        checkNotNull(link.subject) { "a signed link publishes the request it was issued for" }

    private fun append(
        type: String,
        json: String,
        link: SignedLink,
    ) {
        val under = EntityRef(link.alertEntity.value)
        outbox.append(
            listOf(OutboundEvent(type, json)),
            EventContexts.forAggregate(tenants.current(), under, "link:${link.ref.value}"),
        )
    }

    companion object {
        /** The asyncapi channels this class publishes (operations.tsv lists them). */
        const val ISSUED_CHANNEL = "accessSignedLinkIssued"
        const val INVALIDATED_CHANNEL = "accessSignedLinkInvalidated"
        const val ISSUED_TYPE = "com.dynokore.gst.access.SignedLinkIssued.v1"
        const val INVALIDATED_TYPE = "com.dynokore.gst.access.SignedLinkInvalidated.v1"
    }
}
