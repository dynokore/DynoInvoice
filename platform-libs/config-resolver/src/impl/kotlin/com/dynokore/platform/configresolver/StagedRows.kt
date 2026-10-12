package com.dynokore.platform.configresolver

import com.dynokore.platform.outboxandqueue.Envelope
import tools.jackson.databind.ObjectMapper

/**
 * The argument form of a staged row for [ConfigSchema.WRITE_INSTALLATION_SETTING]: a JSON object of the outbox
 * columns the function checks and inserts (`id`, `type`, `source`, `subject`, `tenant_id`, `entity_ref`,
 * `instance_id`, `correlation_id`, `causation_id`, `data`, and `topic` where the stage call gave one). The time is
 * left out, since the function stamps every row with database time (coordinator ruling INV-Q-4316), and so are the
 * Doc UUID, transaction date and `docsequence`, which an installation value's unsequenced platform events never have.
 */
internal class StagedRows(
    private val mapper: ObjectMapper,
) {
    fun json(
        envelope: Envelope,
        topic: String? = null,
    ): String {
        require(envelope.docUuid == null && envelope.transactionDate == null && envelope.docSequence == null) {
            "an installation value's events are unsequenced and carry no document"
        }
        val node = mapper.createObjectNode()
        node.put("id", envelope.id.value)
        node.put("type", envelope.type)
        topic?.let { node.put("topic", it) }
        node.put("source", envelope.source)
        node.put("subject", envelope.subject)
        node.put("tenant_id", envelope.tenant.value.toString())
        node.put("entity_ref", envelope.entity.value)
        node.put("instance_id", envelope.instanceId)
        node.put("correlation_id", envelope.correlation.value)
        node.put("causation_id", envelope.causation.value)
        node.set("data", mapper.readTree(envelope.dataJson))
        return mapper.writeValueAsString(node)
    }
}
