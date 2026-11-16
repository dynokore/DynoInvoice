package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.link.ClientSignatoryConsentCheck
import com.dynokore.gst.access.application.link.ConsentCheck
import com.dynokore.gst.access.application.link.IssueLinkCommand
import com.dynokore.gst.access.application.link.IssueLinkOutcome
import com.dynokore.gst.access.application.link.IssueRefusal
import com.dynokore.gst.access.application.link.IssueSignedLinkUseCase
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import com.dynokore.platform.outboxandqueue.EntityRef as EnvelopeEntity

/**
 * INV-REQ-899, ruling INV-Q-17416 B: issues the signed link on the event `SignedLinkRequested` of masterdata-svc, with
 * no call from a person (owner direction INV-Q-17201). The payload maps as `purpose` to the link purpose,
 * `subjectReference` to the subject, `recipient.email` and `recipient.mobileNumber` to the recipient and `expiry`
 * to the expiry; the alert entity of the link is the entity of the envelope. The CloudEvents id of the delivery is
 * the origin
 * event id, so a redelivery stores no second link and requests no second message (the topic is at-least-once). The
 * `source` of the event is not a sender: the sender of the messages is the system. A payload without a usable
 * subjectReference (1 to 40 characters, the limit of the published event schemas), expiry or recipient e-mail is a
 * [ContractViolation] (poison): no link and no message.
 */
class SignedLinkRequestedHandler(
    private val useCase: IssueSignedLinkUseCase,
    private val messages: OutboxLinkMessages,
    private val mapper: ObjectMapper,
    private val producers: Set<String>,
    private val consentCheck: ClientSignatoryConsentCheck,
    private val clock: Clock,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        if (event.type != TYPE) violation("this handler takes $TYPE, not ${event.type}")
        if (event.service !in producers) violation("${event.service} is not a producer of $TYPE")
        val requestId = event.id.value.also { if (it.isBlank() || it.length > MAX_EVENT_ID) violation("id") }
        val payload = mapper.readTree(event.dataJson)
        val subject = payload.text("subjectReference", MAX_SUBJECT)
        val entity = EnvelopeEntity(event.entity.value)
        val record = { reason: LinkRefusalReason? ->
            messages.outcome(requestId, subject, reason, entity, clock.instant())
        }
        return when (val step = admit(payload, subject, requestId, entity)) {
            is Admission.Refuse -> refused(step.reason, record)
            is Admission.Ready -> issue(step.command, entity, record)
        }
    }

    private sealed interface Admission {
        data class Ready(
            val command: IssueLinkCommand,
        ) : Admission

        data class Refuse(
            val reason: LinkRefusalReason,
        ) : Admission
    }

    /** The command for the use case, or the reason the event is refused before the use case is asked. */
    private fun admit(
        payload: JsonNode,
        subject: String,
        requestId: String,
        entity: EnvelopeEntity,
    ): Admission {
        val purpose = payload.text("purpose", MAX_PURPOSE)
        val recipient = payload.recipient()
        val expiresAt = payload.expiry()
        val kind = kindOf(payload)
        return when {
            recipient == null -> {
                Admission.Refuse(LinkRefusalReason.RECIPIENT_INVALID)
            }

            expiresAt == null -> {
                Admission.Refuse(LinkRefusalReason.EXPIRY_INVALID)
            }

            kind == LinkKind.ClientSignatoryIncomplete -> {
                Admission.Refuse(LinkRefusalReason.CONSENT_NOT_LIVE)
            }

            else -> {
                val live = (kind as? LinkKind.ClientSignatory)?.let { consentCheck.check(it.consentRef, it.clientRef) }
                if (live == ConsentCheck.NotLive) {
                    Admission.Refuse(LinkRefusalReason.CONSENT_NOT_LIVE)
                } else {
                    val pair = live as ConsentCheck.Live?
                    Admission.Ready(
                        IssueLinkCommand(
                            purpose = purpose,
                            subject = subject,
                            email = recipient.first,
                            mobile = recipient.second,
                            expiresAt = expiresAt,
                            consent = pair?.consent,
                            client = pair?.client,
                            envelopeEntity = EntityRef(entity.value),
                            originEventId = requestId,
                        ),
                    )
                }
            }
        }
    }

    private fun issue(
        command: IssueLinkCommand,
        entity: EnvelopeEntity,
        record: (LinkRefusalReason?) -> Unit,
    ): HandleOutcome =
        when (val result = messages.filedUnder(entity) { useCase.handle(command) }) {
            is IssueLinkOutcome.Issued -> {
                record(null)
                HandleOutcome.APPLIED
            }

            // The first delivery wrote its outcome in its own transaction; a redelivery writes none.
            is IssueLinkOutcome.Duplicate -> {
                HandleOutcome.IGNORED
            }

            is IssueLinkOutcome.Refused -> {
                refused(reasonOf(result.reason), record)
            }
        }

    private fun refused(
        reason: LinkRefusalReason,
        record: (LinkRefusalReason?) -> Unit,
    ): HandleOutcome {
        record(reason)
        return HandleOutcome.IGNORED
    }

    private sealed interface LinkKind {
        /** No `linkKind` or `counterparty`: no consent is read. */
        data object Counterparty : LinkKind

        /** A `clientSignatory` event that names no consent or no client entity. */
        data object ClientSignatoryIncomplete : LinkKind

        data class ClientSignatory(
            val consentRef: String,
            val clientRef: String,
        ) : LinkKind
    }

    private fun kindOf(payload: JsonNode): LinkKind {
        val kind = payload.get("linkKind")
        if (kind == null || kind.isNull) return LinkKind.Counterparty
        return when (kind.takeIf { it.isString }?.asString()) {
            KIND_COUNTERPARTY -> {
                LinkKind.Counterparty
            }

            KIND_CLIENT_SIGNATORY -> {
                val consent = payload.optionalText("consentRef", MAX_CONSENT_REF)
                val client = payload.optionalText("clientRef", MAX_CLIENT_REF)
                if (consent == null || client == null) {
                    LinkKind.ClientSignatoryIncomplete
                } else {
                    LinkKind.ClientSignatory(consent, client)
                }
            }

            else -> {
                violation("linkKind is neither $KIND_COUNTERPARTY nor $KIND_CLIENT_SIGNATORY")
            }
        }
    }

    /** The recipient as e-mail and optional mobile number, or null when the recipient is not usable. */
    private fun JsonNode.recipient(): Pair<String, String?>? {
        val node = get("recipient")?.takeIf { it.isObject } ?: return null
        val email = node.usableText("email", MAX_EMAIL) ?: return null
        val mobileNode = node.get("mobileNumber")
        val mobile = if (mobileNode == null || mobileNode.isNull) null else node.usableText("mobileNumber", MAX_MOBILE)
        return if (mobileNode != null && !mobileNode.isNull && mobile == null) null else email to mobile
    }

    /** The text, null when absent, blank or not a string (unusable), and a contract violation when over the schema maximum. */
    private fun JsonNode.usableText(
        field: String,
        max: Int,
    ): String? {
        val text = get(field)?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() } ?: return null
        if (text.length > max) violation("$field is longer than $max")
        return text
    }

    private fun reasonOf(reason: IssueRefusal): LinkRefusalReason =
        when (reason) {
            IssueRefusal.SMS_ONLY_NO_MOBILE, IssueRefusal.SMS_ONLY_GATEWAY_NOT_BOUND -> {
                LinkRefusalReason.MOBILE_NUMBER_REQUIRED
            }

            IssueRefusal.EXPIRY_NOT_IN_FUTURE -> {
                LinkRefusalReason.EXPIRY_INVALID
            }

            IssueRefusal.RECIPIENT_OR_PURPOSE_MISSING -> {
                LinkRefusalReason.RECIPIENT_INVALID
            }

            IssueRefusal.PAIR_INCOMPLETE -> {
                LinkRefusalReason.CONSENT_NOT_LIVE
            }

            // Unreachable from this handler: the envelope always names an entity and the caller is the system actor.
            IssueRefusal.NO_ALERT_ENTITY, IssueRefusal.ACTOR_NOT_SYSTEM -> {
                error("the issue use case refused with $reason, which this handler cannot produce")
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
        val node = get(field)
        return if (node == null || node.isNull) null else text(field, max)
    }

    /** The expiry, or null when it is missing, not a string or not an ISO-8601 instant. */
    private fun JsonNode.expiry(): Instant? {
        val text = get("expiry")?.takeIf { it.isString }?.asString() ?: return null
        return runCatching { Instant.parse(text) }.getOrNull()
    }

    private fun violation(message: String): Nothing = throw ContractViolation(message)

    companion object {
        const val TYPE = "com.dynokore.gst.masterdata.SignedLinkRequested.v1"
        const val TOPIC = "gst.masterdata.signed-link-requested.v1"

        private const val KIND_COUNTERPARTY = "counterparty"
        private const val KIND_CLIENT_SIGNATORY = "clientSignatory"
        private const val MAX_CONSENT_REF = 64
        private const val MAX_CLIENT_REF = 40
        private const val MAX_PURPOSE = 64
        private const val MAX_SUBJECT = 40
        private const val MAX_EMAIL = 254
        private const val MAX_MOBILE = 32
        private const val MAX_EVENT_ID = 128
    }
}
