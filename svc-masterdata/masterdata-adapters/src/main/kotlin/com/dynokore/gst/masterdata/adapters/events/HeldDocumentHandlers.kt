package com.dynokore.gst.masterdata.adapters.events

import com.dynokore.gst.masterdata.application.held.HeldDocumentFact
import com.dynokore.gst.masterdata.application.held.HeldDocuments
import com.dynokore.gst.masterdata.application.held.HeldKind
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TransactionRunner
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** The events and topics masterdata-svc consumes from canonical-svc for the held-documents replica (INV-REQ-941). */
object ConsumedHeldDocumentEvents {
    const val DOCUMENT_HELD = "com.dynokore.gst.canonical.DocumentHeld.v1"
    const val DOCUMENT_HELD_TOPIC = "gst.canonical.document-held.v1"
    const val DOCUMENT_RELEASED = "com.dynokore.gst.canonical.DocumentReleased.v1"
    const val DOCUMENT_RELEASED_TOPIC = "gst.canonical.document-released.v1"

    /** The asyncapi channels these consumers implement (operations.tsv lists them). */
    const val HELD_CHANNEL = "canonicalDocumentHeld"
    const val RELEASED_CHANNEL = "canonicalDocumentReleased"

    /** The emitting service of both events. */
    const val EMITTER = "canonical-svc"

    /** Event type to topic: exactly the two subscriptions of this service for the held-documents replica. */
    val SUBSCRIPTIONS: Map<String, String> =
        mapOf(
            DOCUMENT_HELD to DOCUMENT_HELD_TOPIC,
            DOCUMENT_RELEASED to DOCUMENT_RELEASED_TOPIC,
        )

    /** The published bound of entityRef and partyRef. */
    const val REF_MAX = 40
}

private fun JsonNode.docUuid(): UUID {
    val text =
        this["docUuid"]?.takeIf { it.isString }?.asString()
            ?: throw ContractViolation("payload member docUuid is required")
    val parsed = runCatching { UUID.fromString(text) }.getOrNull()
    if (parsed == null || parsed.toString() != text.lowercase()) throw ContractViolation("docUuid is not a UUID")
    return parsed
}

private fun JsonNode.docsequence(): Long {
    val node = this["docsequence"]
    val value = if (node != null && node.isIntegralNumber && node.canConvertToLong()) node.asLong() else 0L
    if (value < 1) throw ContractViolation("docsequence is an integer of 1 or more")
    return value
}

private fun isErrorCode(node: JsonNode) = node.isString && REASON.matches(node.asString())

private fun JsonNode.reasons(name: String) {
    val node = this[name]
    val valid = node != null && node.isArray && !node.isEmpty && node.all(::isErrorCode)
    if (!valid) throw ContractViolation("payload member $name is a non-empty list of error codes")
}

private fun JsonNode.ref(name: String): String? {
    val node = this[name]?.takeUnless { it.isNull }
    val text = node?.let { if (it.isString) it.asString() else "" }
    val valid = text == null || (text.isNotBlank() && text.length <= ConsumedHeldDocumentEvents.REF_MAX)
    if (!valid) throw ContractViolation("$name is 1 to ${ConsumedHeldDocumentEvents.REF_MAX} characters")
    return text
}

private fun JsonNode.checkTenant(event: Envelope) {
    val tenant = this["tenant"]?.takeUnless { it.isNull } ?: return
    val own = tenant.isString && tenant.asString() == event.tenant.toString()
    if (!own) throw ContractViolation("tenant is not the tenant of the event")
}

private val REASON = Regex("^[A-Z]{2,10}-[0-9]{3}$")

/**
 * DocumentHeld: records the hold of one document with its entity and optional party by the per-document sequence
 * (HeldDocumentRule decides whether the row changes). A payload that breaks the contract is a [ContractViolation]
 * (parked for triage, never dropped); the tenant is the one the event is consumed under.
 */
class DocumentHeldHandler(
    private val tx: TransactionRunner,
    private val held: HeldDocuments,
    private val mapper: ObjectMapper,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        require(event.type == ConsumedHeldDocumentEvents.DOCUMENT_HELD) { "unexpected ${event.type}" }
        val data = mapper.readTree(event.dataJson)
        data.checkTenant(event)
        val docUuid = data.docUuid()
        val sequence = data.docsequence()
        data.reasons("holdReasons")
        val entity = data.ref("entityRef") ?: throw ContractViolation("payload member entityRef is required")
        val party = data.ref("partyRef")
        val fact = HeldDocumentFact(HeldKind.HELD, docUuid, sequence, entity, party)
        return tx.inTransaction {
            held.apply(fact)
            HandleOutcome.APPLIED
        }
    }
}

/** DocumentReleased: records the release of one document as a tombstone that keeps its sequence. */
class DocumentReleasedHandler(
    private val tx: TransactionRunner,
    private val held: HeldDocuments,
    private val mapper: ObjectMapper,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        require(event.type == ConsumedHeldDocumentEvents.DOCUMENT_RELEASED) { "unexpected ${event.type}" }
        val data = mapper.readTree(event.dataJson)
        data.checkTenant(event)
        val docUuid = data.docUuid()
        val sequence = data.docsequence()
        data.reasons("releasedHoldReasons")
        val fact = HeldDocumentFact(HeldKind.RELEASED, docUuid, sequence, null, null)
        return tx.inTransaction {
            held.apply(fact)
            HandleOutcome.APPLIED
        }
    }
}
