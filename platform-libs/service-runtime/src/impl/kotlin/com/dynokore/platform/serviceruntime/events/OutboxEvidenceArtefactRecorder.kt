package com.dynokore.platform.serviceruntime.events

import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import tools.jackson.databind.ObjectMapper

/**
 * [EvidenceArtefactRecorder] over the producing service's outbox (INV-API-3310): one `EvidenceArtefactRecorded`
 * row in the caller's transaction, relayed at least once to `gst.platform.evidence-artefact-recorded.v1`
 * (INV-ADR-283). The payload is exactly the schema's three required fields.
 */
class OutboxEvidenceArtefactRecorder(
    private val outbox: OutboxWriter,
) : EvidenceArtefactRecorder {
    private val mapper = ObjectMapper()

    override fun record(
        artefact: EvidenceArtefact,
        context: EventContext,
    ) {
        val payload = mapper.createObjectNode()
        payload.put("artefactType", artefact.type.value)
        payload.put("reference", artefact.reference)
        payload.put("hash", artefact.hash)
        outbox.append(
            listOf(OutboundEvent(PlatformEventTypes.EVIDENCE_ARTEFACT_RECORDED, mapper.writeValueAsString(payload))),
            context,
        )
    }
}
