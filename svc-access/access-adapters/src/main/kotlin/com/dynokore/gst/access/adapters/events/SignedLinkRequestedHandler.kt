package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.link.IssueLinkCommand
import com.dynokore.gst.access.application.link.IssueLinkOutcome
import com.dynokore.gst.access.application.link.IssueSignedLinkUseCase
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.format.DateTimeParseException
import com.dynokore.platform.outboxandqueue.EntityRef as EnvelopeEntity

/**
 * INV-REQ-899, ruling INV-Q-17416 B: issues the signed link on the event `SignedLinkRequested` of masterdata-svc, with
 * no call from a person (owner direction INV-Q-17201). The payload maps as `purpose` to the link purpose,
 * `subjectReference` to the subject, `recipient.email` and `recipient.mobileNumber` to the recipient and `expiry` to the
 * expiry; the alert entity of the link is the entity of the envelope. The CloudEvents id of the delivery is the origin
 * event id, so a redelivery stores no second link and requests no second message (the topic is at-least-once). The
 * `source` of the event is not a sender: the sender of the messages is the system. A payload without a usable expiry or
 * recipient e-mail is a [ContractViolation] (poison): no link and no message.
 */
class SignedLinkRequestedHandler(
    private val useCase: IssueSignedLinkUseCase,
    private val messages: OutboxLinkMessages,
    private val mapper: ObjectMapper,
    private val producers: Set<String>,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        if (event.type != TYPE) violation("this handler takes $TYPE, not ${event.type}")
        if (event.service !in producers) violation("${event.service} is not a producer of $TYPE")
        val payload = mapper.readTree(event.dataJson)
        val recipient = payload.get("recipient")?.takeIf { it.isObject } ?: violation("recipient is not an object")
        val entity = EnvelopeEntity(event.entity.value)
        val command =
            IssueLinkCommand(
                purpose = payload.text("purpose", MAX_PURPOSE),
                subject = payload.optionalText("subjectReference", MAX_SUBJECT),
                email = recipient.text("email", MAX_EMAIL),
                mobile = recipient.optionalText("mobileNumber", MAX_MOBILE),
                expiresAt = payload.expiry(),
                consent = null,
                client = null,
                envelopeEntity = EntityRef(entity.value),
                originEventId = event.id.value.also { if (it.isBlank() || it.length > MAX_EVENT_ID) violation("id") },
            )
        return when (messages.filedUnder(entity) { useCase.handle(command) }) {
            is IssueLinkOutcome.Issued -> HandleOutcome.APPLIED
            is IssueLinkOutcome.Duplicate -> HandleOutcome.IGNORED
            is IssueLinkOutcome.Refused -> HandleOutcome.IGNORED
        }
    }

    private fun JsonNode.text(
        field: String,
        max: Int,
    ): String {
        val text = get(field)?.takeIf { it.isString }?.asString()
        if (text == null || text.isBlank() || text.length > max) {
            violation("$field is missing, blank, not a string or longer than $max")
        }
        return text
    }

    private fun JsonNode.optionalText(
        field: String,
        max: Int,
    ): String? {
        val node = get(field) ?: return null
        if (node.isNull) return null
        return text(field, max)
    }

    private fun JsonNode.expiry(): Instant {
        val text = get("expiry")?.takeIf { it.isString }?.asString() ?: violation("expiry is not a string")
        return try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            throw ContractViolation("expiry is not an ISO-8601 instant", e)
        }
    }

    private fun violation(message: String): Nothing = throw ContractViolation(message)

    companion object {
        const val TYPE = "com.dynokore.gst.masterdata.SignedLinkRequested.v1"
        const val TOPIC = "gst.masterdata.signed-link-requested.v1"

        /** The producer of the event (model/architecture/events.yaml: producers of SignedLinkRequested). */
        val PRODUCERS = setOf("masterdata-svc")

        private const val MAX_PURPOSE = 64
        private const val MAX_SUBJECT = 256
        private const val MAX_EMAIL = 254
        private const val MAX_MOBILE = 32
        private const val MAX_EVENT_ID = 128
    }
}
