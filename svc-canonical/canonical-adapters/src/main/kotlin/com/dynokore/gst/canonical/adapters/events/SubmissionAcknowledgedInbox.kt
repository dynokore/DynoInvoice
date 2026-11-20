package com.dynokore.gst.canonical.adapters.events

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/** The acknowledgement replica: true when the (tenant, Doc UUID, version) row was new and inserted. */
fun interface AcknowledgementReplica {
    fun record(
        tenantId: UUID,
        docUuid: UUID,
        version: Int,
        acknowledgementId: String,
        acknowledgedAt: OffsetDateTime,
    ): Boolean
}

/**
 * Consumes `SubmissionAcknowledged` (producer submission-svc) into the canonical-local acknowledgement replica
 * (event-carried state transfer, INV-T-25749). The data is checked against
 * `submission/schemas/submission-acknowledged-payload.schema.json`: `docUuid` (uuid), `version` (integer >= 1),
 * `acknowledgementId` (non-empty text), `timestamp` (date-time); what does not validate is a [ContractViolation]
 * (dead letter at once). The tenant is the envelope's; a platform-tenant envelope is a violation. The insert is
 * `ON CONFLICT DO NOTHING` in the handler transaction: APPLIED for a new row, IGNORED for a conflict (the first
 * acknowledgement stored wins). The handler touches no other table and calls no other service.
 */
class SubmissionAcknowledgedInbox(
    private val replica: AcknowledgementReplica,
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        if (event.tenant.isPlatform) throw ContractViolation("the envelope carries no tenantid")
        val data = parse(event.dataJson)
        val docUuid = docUuid(data)
        val version = version(data)
        val acknowledgementId = acknowledgementId(data)
        val timestamp = timestamp(data)
        val added = replica.record(event.tenant.value, docUuid, version, acknowledgementId, timestamp)
        return if (added) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }

    private fun docUuid(data: JsonNode): UUID {
        val value = data.get("docUuid")
        if (value == null || !value.isString || !UUID_FORM.matches(value.asString())) {
            throw ContractViolation("docUuid is missing or not a UUID")
        }
        return UUID.fromString(value.asString())
    }

    private fun version(data: JsonNode): Int {
        val value = data.get("version")
        val number = if (value != null && value.isNumber) value.decimalValue() else null
        if (number == null || number.stripTrailingZeros().scale() > 0 ||
            number.signum() < 1 || number > Int.MAX_VALUE.toBigDecimal()
        ) {
            throw ContractViolation("version is missing or not an integer of at least 1")
        }
        return number.intValueExact()
    }

    private fun acknowledgementId(data: JsonNode): String {
        val value = data.get("acknowledgementId")
        if (value == null || !value.isString || value.asString().isBlank()) {
            throw ContractViolation("acknowledgementId is missing or not a non-empty text")
        }
        return value.asString()
    }

    private fun timestamp(data: JsonNode): OffsetDateTime {
        val value = data.get("timestamp")
        if (value == null || !value.isString) throw ContractViolation("timestamp is missing or not a date-time")
        return try {
            OffsetDateTime.parse(value.asString())
        } catch (e: DateTimeParseException) {
            throw ContractViolation("timestamp is not a date-time", e)
        }
    }

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text).also { if (!it.isObject) throw ContractViolation("the payload is not a JSON object") }
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private companion object {
        val UUID_FORM = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
