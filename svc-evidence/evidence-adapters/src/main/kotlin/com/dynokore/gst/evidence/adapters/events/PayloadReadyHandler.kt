package com.dynokore.gst.evidence.adapters.events

import com.dynokore.gst.evidence.application.payload.TransmittedPayloadSealer
import com.dynokore.gst.evidence.domain.payload.HeldPayload
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.Base64
import java.util.UUID

/**
 * The consumer of `gst.payload.payload-ready.v1` (INV-REQ-645 behavior 1): holds the payload bytes until the
 * despatch arrives, or seals the parked despatches, through [TransmittedPayloadSealer], in the inbox consumer's
 * transaction. The envelope id is the event id. A payload that is not JSON, or whose `docUuid`, `version`,
 * `payloadBytes` (base64) or `payloadSha256` is missing or malformed, is refused with [ContractViolation]: a poison
 * record that writes nothing.
 */
class PayloadReadyHandler(
    private val sealer: TransmittedPayloadSealer,
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val held = HeldPayload(uuid(data), version(data), bytes(data), digest(data))
        sealer.onPayloadReady(event.tenant, event.id.value, held)
        return HandleOutcome.APPLIED
    }

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text)
        } catch (e: tools.jackson.core.JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private fun uuid(data: JsonNode): UUID =
        runCatching { UUID.fromString(data.get("docUuid")?.takeIf { it.isString }?.asString()) }
            .getOrElse { throw ContractViolation("docUuid is missing or not a UUID", it) }

    private fun version(data: JsonNode): Int =
        data.get("version")?.takeIf { it.isIntegralNumber && it.canConvertToInt() && it.asInt() >= 1 }?.asInt()
            ?: throw ContractViolation("version is missing or not a positive integer")

    private fun bytes(data: JsonNode): ByteArray =
        runCatching { Base64.getDecoder().decode(data.get("payloadBytes")?.takeIf { it.isString }?.asString()) }
            .getOrElse { throw ContractViolation("payloadBytes is missing or not base64", it) }

    private fun digest(data: JsonNode): String =
        data.get("payloadSha256")?.takeIf { it.isString && it.asString().isNotBlank() }?.asString()
            ?: throw ContractViolation("payloadSha256 is missing or blank")
}
