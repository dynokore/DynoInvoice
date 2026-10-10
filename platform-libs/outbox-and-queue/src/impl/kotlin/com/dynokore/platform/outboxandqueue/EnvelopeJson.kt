package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * The CloudEvents 1.0 structured-JSON form of an [Envelope] (API-40; platform-contracts
 * `cloud-event-envelope.schema.json`), as stored in the event log. Reading enforces the
 * FR-K.3 fields the contract schema leaves optional (ARC 4.2 step 1): a record that
 * fails is a [ContractViolation], never handed to a handler.
 */
class EnvelopeJson(
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    /** The structured JSON text of [envelope]. */
    fun write(envelope: Envelope): String {
        val node = mapper.createObjectNode()
        node.put("specversion", SPEC_VERSION)
        node.put("id", envelope.id.value)
        node.put("source", envelope.source)
        node.put("type", envelope.type)
        node.put("subject", envelope.subject)
        node.put("time", envelope.time.toString())
        node.put("datacontenttype", JSON)
        node.put("tenantid", envelope.tenant.toString())
        node.put("entityref", envelope.entity.value)
        envelope.transactionDate?.let { node.put("transactiondate", it.toString()) }
        envelope.docSequence?.let { node.put("docsequence", it) }
        node.put("instanceid", envelope.instanceId)
        node.put("correlationid", envelope.correlation.value)
        node.put("causationid", envelope.causation.value)
        node.set("data", mapper.readTree(envelope.dataJson))
        return mapper.writeValueAsString(node)
    }

    /** The `tenantid` of [json] when it parses as a UUID, even if the record is otherwise invalid; `null` if not. */
    fun tenantOf(json: String): TenantId? = try {
        mapper.readTree(json).get("tenantid")?.takeIf { it.isString }?.asString()?.let { TenantId.of(it) }
    } catch (_: tools.jackson.core.JacksonException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Parses and validates [json]; throws [ContractViolation] when it is not a complete envelope. */
    fun read(json: String): Envelope {
        val node =
            try {
                mapper.readTree(json)
            } catch (failure: tools.jackson.core.JacksonException) {
                throw ContractViolation("record is not JSON", failure)
            }
        if (node !is ObjectNode) throw ContractViolation("record is not a JSON object")
        if (text(node, "specversion") != SPEC_VERSION) throw ContractViolation("specversion must be $SPEC_VERSION")
        return try {
            build(node)
        } catch (failure: IllegalArgumentException) {
            throw ContractViolation("invalid envelope: ${failure.message}", failure)
        } catch (failure: DateTimeParseException) {
            throw ContractViolation("invalid envelope date: ${failure.message}", failure)
        }
    }

    private fun build(node: ObjectNode): Envelope = Envelope(
        id = EventId(text(node, "id")),
        source = text(node, "source"),
        type = text(node, "type"),
        subject = text(node, "subject"),
        time = Instant.parse(text(node, "time")),
        tenant = TenantId.of(text(node, "tenantid")),
        entity = EntityRef(text(node, "entityref")),
        docUuid = docUuidOf(text(node, "subject")),
        transactionDate = optional(node, "transactiondate")?.let { LocalDate.parse(it) },
        docSequence = docSequence(node),
        instanceId = text(node, "instanceid"),
        correlation = CorrelationId(text(node, "correlationid")),
        causation = CausationId(text(node, "causationid")),
        dataJson = mapper.writeValueAsString(node.get("data") ?: throw ContractViolation("data is missing")),
    )

    // The contract schema has no Doc UUID attribute: `subject` is the Doc UUID where the event concerns a document
    // (FR-K.3), and EventContext forces that, so a UUID subject is the Doc UUID.
    private fun docUuidOf(subject: String): DocUuid? = try {
        DocUuid(UUID.fromString(subject))
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun docSequence(node: ObjectNode): Long? {
        val value = node.get("docsequence") ?: return null
        if (!value.isIntegralNumber) throw ContractViolation("docsequence must be an integer")
        return value.asLong()
    }

    private fun optional(
        node: JsonNode,
        field: String,
    ): String? = node.get(field)?.takeUnless { it.isNull }?.asString()

    private fun text(
        node: JsonNode,
        field: String,
    ): String {
        val value = node.get(field)
        if (value == null || !value.isString || value.asString().isBlank()) {
            throw ContractViolation("$field is required")
        }
        return value.asString()
    }

    private companion object {
        const val SPEC_VERSION = "1.0"
        const val JSON = "application/json"
    }
}
