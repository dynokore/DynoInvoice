package com.dynokore.gst.evidence.adapters.events

import com.dynokore.gst.evidence.application.artefact.ArtefactRecorder
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.serviceruntime.events.EvidenceArtefact
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/**
 * The consumer of `gst.platform.evidence-artefact-recorded.v1` (INV-REQ-1036 behavior 2): stores the event's
 * `artefactType`, `reference` and `hash` once per tenant, with the envelope's entity as `entity_ref`.
 *
 * An event whose data lacks one of the three fields, whose type is not UPPER_SNAKE_CASE or whose hash is not 64
 * lowercase hex characters is refused with [ContractViolation]: a poison record that stores nothing. A well-formed
 * type outside the known set is stored as received.
 */
class EvidenceArtefactRecordedHandler(
    private val recorder: ArtefactRecorder,
    private val clock: Clock = Clock.systemUTC(),
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val artefact =
            try {
                EvidenceArtefact(
                    EvidenceArtefactType(text(data, "artefactType")),
                    text(data, "reference"),
                    text(data, "hash"),
                )
            } catch (e: IllegalArgumentException) {
                throw ContractViolation("the artefact is malformed: ${e.message}", e)
            }
        recorder.record(event.tenant, event.entity.value, artefact, event.id.value, clock.instant())
        return HandleOutcome.APPLIED
    }

    private fun text(
        data: JsonNode,
        name: String,
    ): String {
        val node = data.get(name)
        if (node == null || !node.isString) throw ContractViolation("the artefact has no text $name")
        return node.asString()
    }

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text).also { if (!it.isObject) throw ContractViolation("the payload is not a JSON object") }
        } catch (e: tools.jackson.core.JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }
}
