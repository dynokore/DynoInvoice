package com.dynokore.gst.evidence.adapters.events

import com.dynokore.gst.evidence.application.chain.SubmissionIndexStore
import com.dynokore.gst.evidence.application.payload.TransmittedPayloadSealer
import com.dynokore.gst.evidence.domain.payload.Despatch
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The consumer of `gst.submission.submission-despatched.v1` (INV-REQ-645 behavior 1): seals the despatched version,
 * or parks the despatch until its PayloadReady arrives, through [TransmittedPayloadSealer], and sets state SENT on
 * the `submission_index` row of the version in the same transaction (INV-REQ-724). `despatchedAt` is the
 * CloudEvents `time` of the envelope (gap ruling, owner 2026-10-14, option A), never the consumer clock, so a
 * redelivery or replay seals the same bytes. A malformed payload is refused with [ContractViolation].
 */
class SubmissionDespatchedHandler(
    private val sealer: TransmittedPayloadSealer,
    private val index: SubmissionIndexStore,
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val despatch =
            Despatch(uuid(data), version(data), attempt(data), digest(data), event.time)
        sealer.onDespatched(event.tenant, event.id.value, despatch)
        index.markDespatched(event.tenant, despatch.docUuid, despatch.version)
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

    private fun version(data: JsonNode): Int = positive(data, "version")

    private fun attempt(data: JsonNode): Int = positive(data, "attemptNumber")

    private fun positive(
        data: JsonNode,
        name: String,
    ): Int =
        data.get(name)?.takeIf { it.isIntegralNumber && it.canConvertToInt() && it.asInt() >= 1 }?.asInt()
            ?: throw ContractViolation("$name is missing or not a positive integer")

    private fun digest(data: JsonNode): String =
        data.get("payloadSha256")?.takeIf { it.isString && it.asString().isNotBlank() }?.asString()
            ?: throw ContractViolation("payloadSha256 is missing or blank")
}
