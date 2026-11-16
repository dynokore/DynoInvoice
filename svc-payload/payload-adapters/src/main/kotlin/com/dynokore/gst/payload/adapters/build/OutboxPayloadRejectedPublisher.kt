package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.application.build.PayloadRejectedFacts
import com.dynokore.gst.payload.application.build.PayloadRejectedPublisher
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * [PayloadRejectedPublisher] over the transactional outbox, as [OutboxOverrideAudit]-style staging: the event is
 * appended in the open transaction of the caller, so a rollback leaves no event. `docsequence` is the number
 * `appendSequenced` reserves for the same row. `validationTrace` is an object (published schema): an opaque trace that
 * is a JSON object is embedded as given, any other text is wrapped as `{"reason": <text>}`, and a catalogue code
 * without a trace gives `{"code": <code>}`; `code` is written only when set.
 * `packVersions` (additive, API-41) is written only when [PayloadRejectedFacts.packVersions] is non-empty.
 */
class OutboxPayloadRejectedPublisher(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper = ObjectMapper(),
) : PayloadRejectedPublisher {
    override fun stage(facts: PayloadRejectedFacts) {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        outbox.appendSequenced(
            EVENT_TYPE,
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(facts.entityRef),
                docUuid = DocUuid(facts.docUuid),
                transactionDate = null,
                aggregateKey = facts.docUuid.toString(),
                aggregateVersion = facts.version.toLong(),
                correlation = CorrelationId(correlation),
                causation = CausationId(causation),
            ),
        ) { docSequence -> payload(facts, docSequence) }
    }

    private fun payload(
        facts: PayloadRejectedFacts,
        docSequence: Long,
    ): String {
        val node = mapper.createObjectNode()
        node.put("docUuid", facts.docUuid.toString())
        node.put("version", facts.version)
        node.put("docsequence", docSequence)
        node.set("validationTrace", trace(facts))
        facts.code?.let { node.put("code", it) }
        facts.packVersions?.takeIf { it.isNotEmpty() }?.let { packs ->
            val array = node.putArray("packVersions")
            packs.forEach { array.addObject().put("packType", it.packType).put("version", it.version) }
        }
        return mapper.writeValueAsString(node)
    }

    private fun trace(facts: PayloadRejectedFacts): JsonNode {
        val text = facts.validationTrace
        if (text == null) {
            requireNotNull(facts.code) { "a rejection carries a code or a validation trace" }
            return mapper.createObjectNode().put("code", facts.code)
        }
        val parsed: JsonNode? = runCatching { mapper.readTree(text) }.getOrNull()
        return if (parsed is ObjectNode) parsed else mapper.createObjectNode().put("reason", text)
    }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.payload.PayloadRejected.v1"
    }
}
