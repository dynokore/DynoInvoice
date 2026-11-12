package com.dynokore.gst.evidence.adapters.events

import com.dynokore.gst.evidence.application.chain.AcknowledgementRecorder
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The consumer of `gst.submission.submission-acknowledged.v1` (INV-REQ-691): stores the locator of the
 * `acknowledgementId` and appends the event as an ACKNOWLEDGEMENT record of (docUuid, version), in the inbox
 * consumer's transaction.
 *
 * A payload that is not JSON, or whose `docUuid` is not a UUID, whose `version` is not a positive integer or whose
 * `acknowledgementId` is missing or blank, is refused with [ContractViolation]: a poison record that writes nothing.
 */
class SubmissionAcknowledgedHandler(
    private val recorder: AcknowledgementRecorder,
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val ackId =
            data.get("acknowledgementId")?.takeIf { it.isString && it.asString().isNotBlank() }?.asString()
                ?: throw ContractViolation("acknowledgementId is missing or blank")
        recorder.record(
            tenant = event.tenant,
            eventId = event.id.value,
            ackId = ackId,
            docUuid = uuid(data),
            version = version(data),
            content = CanonicalJson.bytes(data),
        )
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
}
