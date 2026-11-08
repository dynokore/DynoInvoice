package com.dynokore.gst.tenancy.adapters.rendition

import com.dynokore.gst.tenancy.application.rendition.ApplyRenditionFinding
import com.dynokore.gst.tenancy.application.rendition.FindingResult
import com.dynokore.gst.tenancy.application.rendition.RenditionFindingChanged
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** The inbox subscription of tenancy-svc to `RenditionFindingChanged` (INV-Q-16391), by exact name. */
object ConsumedRenditionEvents {
    const val CONSUMER = "tenancy-svc-rendition-finding-changed"
    const val TYPE = "com.dynokore.gst.payload.RenditionFindingChanged.v1"
    const val TOPIC = "gst.payload.rendition-finding-changed.v1"
}

/**
 * RenditionFindingChanged (payload: entityRef, open, code, sequence, changedAt) is applied through
 * [ApplyRenditionFinding] in the inbox consumer's transaction: the same event twice, or an older sequence, changes
 * nothing. A payload that breaks the contract goes to the dead letter.
 */
class RenditionFindingChangedHandler(
    private val apply: ApplyRenditionFinding,
    private val mapper: ObjectMapper,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        require(event.type == ConsumedRenditionEvents.TYPE) { "unexpected ${event.type}" }
        val data =
            try {
                mapper.readTree(event.dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("the payload is not JSON", e)
            }
        val entityRef = data.text("entityRef")
        val open = data["open"]?.takeIf { it.isBoolean }?.asBoolean() ?: throw ContractViolation("open is required")
        val sequence =
            data["sequence"]?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()?.takeIf { it >= 1 }
                ?: throw ContractViolation("sequence is required and at least 1")
        return when (apply.handle(RenditionFindingChanged(entityRef, open, sequence))) {
            FindingResult.APPLIED -> HandleOutcome.APPLIED
            FindingResult.IGNORED -> HandleOutcome.IGNORED
        }
    }

    private fun JsonNode.text(name: String): String =
        this[name]?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
            ?: throw ContractViolation("payload member $name is required")
}
