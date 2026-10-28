package com.dynokore.gst.evidence.adapters.events

import com.dynokore.gst.evidence.application.digest.DigestObjectConflict
import com.dynokore.gst.evidence.application.digest.FiledReturn
import com.dynokore.gst.evidence.application.digest.PeriodDigestRecorder
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * The consumer of `gst.reconciliation.gst-return-recorded.v1` (INV-Q-7251 A (1)): the filing of a return closes its
 * period, so the event writes the period digest of the entity's three document chains. It runs in the inbox consumer's
 * transaction, so the digest rows, the inbox row and the offset commit together; the dedupe on the event id is the
 * inbox's.
 *
 * A payload without a usable `entityRef`, `period`, `returnVersion` or `filedAt`, and an object store key that holds
 * other bytes than the digest, are refused with [ContractViolation]: poison records that go to the dead letter.
 */
class GstReturnRecordedHandler(
    private val recorder: PeriodDigestRecorder,
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        val data =
            try {
                json.readTree(event.dataJson)
            } catch (e: tools.jackson.core.JacksonException) {
                throw ContractViolation("the payload is not JSON", e)
            }
        val filed =
            FiledReturn(
                eventId = event.id.value,
                entityRef = text(data, "entityRef"),
                period = text(data, "period"),
                returnVersion = returnVersion(data),
                filedAt = filedAt(data),
            )
        try {
            recorder.record(event.tenant, filed)
        } catch (e: DigestObjectConflict) {
            throw ContractViolation("the object ${e.key} holds other bytes than the digest", e)
        }
        return HandleOutcome.APPLIED
    }

    private fun text(
        data: JsonNode,
        name: String,
    ): String =
        data.get(name)?.takeIf { it.isString && it.asString().isNotBlank() }?.asString()
            ?: throw ContractViolation("$name is missing or not a text")

    private fun returnVersion(data: JsonNode): Int =
        data.get("returnVersion")?.takeIf { it.isIntegralNumber && it.canConvertToInt() && it.asInt() >= 1 }?.asInt()
            ?: throw ContractViolation("returnVersion is missing or not a positive integer")

    private fun filedAt(data: JsonNode): Instant =
        runCatching { Instant.parse(data.get("filedAt")?.takeIf { it.isString }?.asString()) }
            .getOrElse { throw ContractViolation("filedAt is missing or not a timestamp", it) }
}
