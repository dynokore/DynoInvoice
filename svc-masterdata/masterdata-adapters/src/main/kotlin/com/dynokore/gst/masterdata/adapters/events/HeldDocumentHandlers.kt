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
    if (node == null || !node.isIntegralNumber || !node.canConvertToLong() || node.asLong() < 1) {
        throw ContractViolation("docsequence is an integer of 1 or more")
    }
    return node.asLong()
}

private fun JsonNode.reasons(name: String) {
    val node = this[name]
    if (node == null || !node.isArray || node.isEmpty || node.any { !it.isString || !REASON.matches(it.asString()) }) {
        throw ContractViolation("payload member $name is a non-empty list of error codes")
    }
}

private fun JsonNode.ref(name: String): String? {
    val node = this[name] ?: return null
    if (node.isNull) return null
    val text = if (node.isString) node.asString() else null
    if (text.isNullOrBlank() || text.length > ConsumedHeldDocumentEvents.REF_MAX) {
        throw ContractViolation("$name is 1 to ${ConsumedHeldDocumentEvents.REF_MAX} characters")
    }
    return text
}

private fun JsonNode.checkTenant(event: Envelope) {
    val tenant = this["tenant"]
    if (tenant != null && !tenant.isNull && (!tenant.isString || tenant.asString() != event.tenant.toString())) {
        throw ContractViolation("tenant is not the tenant of the event")
    }
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
