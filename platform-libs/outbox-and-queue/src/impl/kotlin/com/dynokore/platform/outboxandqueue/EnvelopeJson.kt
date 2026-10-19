package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.format.DateTimeParseException

/**
 * The CloudEvents 1.0 structured-JSON form of an [Envelope] (API-40; platform-contracts
 * `cloud-event-envelope.schema.json`), as stored in the event log. Reading enforces the
 * FR-K.3 fields the contract schema leaves optional (ARC 4.2 step 1): a record that
 * fails is a [ContractViolation], never handed to a handler. A platform event under the reserved
 * platform tenant travels without `tenantid` and `entityref`, and reads back as `TenantId.PLATFORM`
 * and [EntityRef.PLATFORM] (INV-Q-2927 option D); no other event may omit them (INV-Q-3018).
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
        // INV-Q-2927 option D: the reserved platform tenant and entity never go on the wire.
        if (!envelope.tenant.isPlatform) {
            node.put("tenantid", envelope.tenant.toString())
            // INV-Q-3165: the reserved tenant-wide entity never goes on the wire either.
            if (!envelope.entity.isTenantWide) node.put("entityref", envelope.entity.value)
        }
        envelope.transactionDate?.let { node.put("transactiondate", it.toString()) }
        envelope.docSequence?.let { node.put("docsequence", it) }
        node.put("instanceid", envelope.instanceId)
        node.put("correlationid", envelope.correlation.value)
        node.put("causationid", envelope.causation.value)
        node.set("data", mapper.readTree(envelope.dataJson))
        return mapper.writeValueAsString(node)
    }

    /**
     * The tenant of [json] even if the record is otherwise invalid: its `tenantid` when that parses as a
     * UUID other than the reserved platform id; `TenantId.PLATFORM` when `tenantid` is absent and the type
     * is a platform type (INV-Q-2927 option D); `null` otherwise.
     */
    fun tenantOf(json: String): TenantId? =
        try {
            val node = mapper.readTree(json)
            val text = node.get("tenantid")
            when {
                text == null && node
                    .get(
                        "type",
                    )?.takeIf { it.isString }
                    ?.asString()
                    ?.let(Envelope::isPlatformType) == true -> {
                    TenantId.PLATFORM
                }

                else -> {
                    text?.takeIf { it.isString }?.asString()?.let { TenantId.fromClaim(it) }
                }
            }
        } catch (_: tools.jackson.core.JacksonException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    /** Parses and validates [json]; throws [ContractViolation] when it is not a complete envelope. */
    fun read(json: String): Envelope {
        val node = parseTree(json)
        if (node !is ObjectNode) throw ContractViolation("record is not a JSON object")
        if (EnvelopeFields.text(node, "specversion") !=
            SPEC_VERSION
        ) {
            throw ContractViolation("specversion must be $SPEC_VERSION")
        }
        return buildChecked(node)
    }

    private fun parseTree(json: String): JsonNode =
        try {
            mapper.readTree(json)
        } catch (failure: tools.jackson.core.JacksonException) {
            throw ContractViolation("record is not JSON", failure)
        }

    private fun buildChecked(node: ObjectNode): Envelope =
        try {
            EnvelopeFields.build(node, mapper)
        } catch (failure: IllegalArgumentException) {
            throw ContractViolation("invalid envelope: ${failure.message}", failure)
        } catch (failure: DateTimeParseException) {
            throw ContractViolation("invalid envelope date: ${failure.message}", failure)
        }

    private companion object {
        const val SPEC_VERSION = EnvelopeFields.SPEC_VERSION
        const val JSON = "application/json"
    }
}
