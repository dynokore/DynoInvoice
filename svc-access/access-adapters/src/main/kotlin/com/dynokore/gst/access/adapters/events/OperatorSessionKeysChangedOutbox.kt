package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.domain.revocation.VerificationKey
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * TPL-05: maps the verification keys that are valid to the contract event OperatorSessionKeysChanged on
 * `gst.access.operator-session-keys-changed.v1` (coordinator rulings INV-Q-4162, INV-Q-4171) and appends it through
 * outbox-and-queue in the transaction the caller holds. One event carries the whole of both key sets, as the authority
 * replica replaces what it holds; every item carries its `use`. The payload is the
 * `operator-session-keys-changed-payload` schema of platform-contracts, at most [MAX_KEYS] items.
 */
class OperatorSessionKeysChangedOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
) {
    fun publish(valid: List<VerificationKey>) {
        val context = EventContexts.forAggregate(TenantId.PLATFORM, EntityRef.PLATFORM, AGGREGATE_KEY)
        outbox.append(listOf(OutboundEvent(EVENT_TYPE, payload(valid).toString())), context)
    }

    internal fun payload(valid: List<VerificationKey>): ObjectNode {
        check(valid.size in 1..MAX_KEYS) {
            "an OperatorSessionKeysChanged carries 1 to $MAX_KEYS keys, not ${valid.size}"
        }
        val root = mapper.createObjectNode()
        val items = root.putArray("keys")
        valid.sortedBy { it.kid }.forEach { key ->
            val node = items.addObject()
            node.put("kid", key.kid)
            node.put("use", key.use.name)
            node.put("alg", key.alg)
            node.put("publicKey", key.publicKey)
            node.put("validFrom", ISO.format(key.validFrom.atOffset(ZoneOffset.UTC)))
            node.put("validUntil", ISO.format(key.validUntil.atOffset(ZoneOffset.UTC)))
        }
        return root
    }

    companion object {
        /** The asyncapi channel this class publishes (operations.tsv lists it). */
        const val CHANNEL = "accessOperatorSessionKeysChanged"
        const val EVENT_TYPE = "com.dynokore.gst.access.OperatorSessionKeysChanged.v1"
        const val MAX_KEYS = 4
        private const val AGGREGATE_KEY = "operator-session-keys"
        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
