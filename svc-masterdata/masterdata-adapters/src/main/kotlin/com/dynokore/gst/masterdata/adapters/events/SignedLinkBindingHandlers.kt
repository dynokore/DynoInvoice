package com.dynokore.gst.masterdata.adapters.events

import com.dynokore.gst.masterdata.adapters.persistence.JooqLinkBindings
import com.dynokore.gst.masterdata.application.request.CounterpartyRequests
import com.dynokore.gst.masterdata.application.request.SubmitCounterpartyDetails
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TransactionRunner
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.format.DateTimeParseException

/** The events and topics masterdata-svc consumes from access-svc for the link bindings (INV-REQ-901, FR-AF.23). */
object ConsumedSignedLinkEvents {
    const val SIGNED_LINK_ISSUED = "com.dynokore.gst.access.SignedLinkIssued.v1"
    const val SIGNED_LINK_ISSUED_TOPIC = "gst.access.signed-link-issued.v1"
    const val SIGNED_LINK_INVALIDATED = "com.dynokore.gst.access.SignedLinkInvalidated.v1"
    const val SIGNED_LINK_INVALIDATED_TOPIC = "gst.access.signed-link-invalidated.v1"

    /** The asyncapi channels these consumers implement (operations.tsv lists them). */
    const val ISSUED_CHANNEL = "accessSignedLinkIssued"
    const val INVALIDATED_CHANNEL = "accessSignedLinkInvalidated"

    /** The emitting service of both events. */
    const val EMITTER = "access-svc"

    /** Event type to topic: exactly the two subscriptions of this service for the link bindings. */
    val SUBSCRIPTIONS: Map<String, String> =
        mapOf(
            SIGNED_LINK_ISSUED to SIGNED_LINK_ISSUED_TOPIC,
            SIGNED_LINK_INVALIDATED to SIGNED_LINK_INVALIDATED_TOPIC,
        )

    /** The published form of a linkRef (access payload schemas). */
    val LINK_REF = Regex("^LNK-[A-Za-z0-9_-]{22}$")
    const val SUBJECT_MAX = 40
}

private fun JsonNode.text(name: String): String =
    this[name]?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
        ?: throw ContractViolation("payload member $name is required")

private fun JsonNode.linkRef(): String =
    text("linkRef").also {
        if (!ConsumedSignedLinkEvents.LINK_REF.matches(it)) throw ContractViolation("linkRef is not a link reference")
    }

private fun JsonNode.subject(): String =
    text("subjectReference").also {
        if (it.length > ConsumedSignedLinkEvents.SUBJECT_MAX) throw ContractViolation("subjectReference is too long")
    }

private fun JsonNode.instant(name: String): Instant =
    try {
        Instant.parse(text(name))
    } catch (_: DateTimeParseException) {
        throw ContractViolation("payload member $name is not a date-time")
    }

/**
 * SignedLinkIssued: binds the linkRef to the request named by `subjectReference`, the tenant and the expiry. A payload
 * that does not match the contract, or names another tenant than the one the event is consumed under, is a
 * [ContractViolation] (parked for triage, never dropped). An event that names no request of this tenant is ignored
 * (asyncapi `receiveSignedLinkIssued`). A repeat leaves the row equal and an issue after an invalidation does not
 * reopen the link: the invalidation instant stays.
 */
class SignedLinkIssuedHandler(
    private val tx: TransactionRunner,
    private val submit: SubmitCounterpartyDetails,
    private val requests: CounterpartyRequests,
    private val bindings: JooqLinkBindings,
    private val mapper: ObjectMapper,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        require(event.type == ConsumedSignedLinkEvents.SIGNED_LINK_ISSUED) { "unexpected ${event.type}" }
        val data = mapper.readTree(event.dataJson)
        val linkRef = data.linkRef()
        val subject = data.subject()
        data.text("purpose")
        val tenant = data.text("tenant")
        if (tenant != event.tenant.toString()) throw ContractViolation("tenant is not the tenant of the event")
        val expiry = data.instant("expiry")
        return tx.inTransaction {
            if (requests.find(subject) == null) {
                HandleOutcome.IGNORED
            } else {
                submit.applyIssued(linkRef, tenant, subject, expiry)
                bindings.recordEvent(linkRef, event.id.toString())
                HandleOutcome.APPLIED
            }
        }
    }
}

/**
 * SignedLinkInvalidated: marks the link unusable from `invalidatedAt`, whatever its reason (an unknown reason is
 * invalidated too, API-41). An invalidation that arrives before the issue creates the row invalidated; the earliest
 * instant is kept, so a repeat or a reordered pair leaves the row equal.
 */
class SignedLinkInvalidatedHandler(
    private val tx: TransactionRunner,
    private val submit: SubmitCounterpartyDetails,
    private val bindings: JooqLinkBindings,
    private val mapper: ObjectMapper,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        require(event.type == ConsumedSignedLinkEvents.SIGNED_LINK_INVALIDATED) { "unexpected ${event.type}" }
        val data = mapper.readTree(event.dataJson)
        val linkRef = data.linkRef()
        data.subject()
        data.text("reason")
        val at = data.instant("invalidatedAt")
        return tx.inTransaction {
            submit.applyInvalidated(linkRef, at)
            bindings.recordEvent(linkRef, event.id.toString())
            HandleOutcome.APPLIED
        }
    }
}
