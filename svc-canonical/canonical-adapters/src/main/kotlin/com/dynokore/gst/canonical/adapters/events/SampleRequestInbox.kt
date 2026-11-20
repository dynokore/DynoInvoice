package com.dynokore.gst.canonical.adapters.events

import com.dynokore.gst.canonical.application.sample.SampleRequest
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** The entity id of ([tenantId], [entityRef]) in the event-fed identity table, or null when none. */
fun interface SampleEntityIdentities {
    fun entityIdOf(
        tenantId: UUID,
        entityRef: String,
    ): UUID?
}

/** The marker of an answered request: true when the (tenant, dry run id) marker was new and inserted. */
fun interface SampleRequestMarker {
    fun markAnswered(
        tenantId: UUID,
        dryRunId: String,
    ): Boolean
}

/** The use case that answers one request (the `AnswerSampleRequest` of the application layer). */
fun interface SampleAnswer {
    fun answer(request: SampleRequest)
}

/**
 * Consumes `PackDryRunSampleRequested` (platform event, INV-REQ-772 b1) with the tenant binding of the request's
 * envelope. The handler runs in the consumer's tenant transaction, so the marker, the pages the use case hands to the
 * outbox and the inbox row commit together; any exception (an unknown entity, a refusal of the use case) rolls all of
 * them back and the event is retried. The marker is inserted first (`ON CONFLICT DO NOTHING`): an insert that adds no
 * row means the dry-run id was already answered for the tenant, and nothing is published. The data is checked against
 * `service-runtime/schemas/pack-dry-run-sample-requested-payload.schema.json`; what does not validate is a
 * [ContractViolation] (dead letter at once). A platform-tenant envelope is a violation: the request names a tenant.
 */
class SampleRequestInbox(
    private val identities: SampleEntityIdentities,
    private val markers: SampleRequestMarker,
    private val answer: SampleAnswer,
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        if (event.tenant.isPlatform) throw ContractViolation("the envelope carries no tenantid")
        val tenant = event.tenant.value
        val data = parse(event.dataJson)
        val dryRunId = text(data, "dryRunId", MAX_ID)
        val entityRef = text(data, "entityRef", MAX_ID)
        val first = text(data, "firstPeriodRef", MAX_PERIOD)
        val last = text(data, "lastPeriodRef", MAX_PERIOD)
        count(data)
        text(data, "currentPackVersion", MAX_PERIOD)
        text(data, "candidatePackVersion", MAX_PERIOD)
        // Fail closed: an entity without an identity row for this tenant is refused, never answered with a made-up id.
        val entityId =
            identities.entityIdOf(tenant, entityRef)
                ?: error("entity $entityRef has no identity row for the tenant of the request")
        if (!markers.markAnswered(tenant, dryRunId)) return HandleOutcome.IGNORED
        answer.answer(SampleRequest(tenant, dryRunId, entityRef, entityId, first, last))
        return HandleOutcome.APPLIED
    }

    private fun text(
        data: JsonNode,
        member: String,
        max: Int,
    ): String {
        val value = data.get(member)
        if (value == null || !value.isString || value.asString().length !in 1..max) {
            throw ContractViolation("$member is missing or not a text of 1 to $max characters")
        }
        return value.asString()
    }

    private fun count(data: JsonNode) {
        val value = data.get("periodCount")
        val number = if (value != null && value.isNumber) value.decimalValue() else null
        if (number == null || number.stripTrailingZeros().scale() > 0 || number.signum() < 1) {
            throw ContractViolation("periodCount is missing or not an integer of at least 1")
        }
    }

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text).also { if (!it.isObject) throw ContractViolation("the payload is not a JSON object") }
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private companion object {
        const val MAX_ID = 40
        const val MAX_PERIOD = 32
    }
}
