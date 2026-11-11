package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.domain.revocation.VerificationKey
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One item of the key feed: a public key with the `use` it verifies (`LINK_SESSION` has no domain enum value). */
data class PublishedKey(
    val kid: String,
    val use: String,
    val alg: String,
    val publicKey: String,
    val validFrom: Instant,
    val validUntil: Instant,
) {
    companion object {
        fun of(key: VerificationKey) =
            PublishedKey(key.kid, key.use.name, key.alg, key.publicKey, key.validFrom, key.validUntil)
    }
}

/**
 * TPL-05: maps the verification keys that are valid to the contract event OperatorSessionKeysChanged on
 * `gst.platform.operator-session-keys-changed.v1` (INV-Q-16498 A, amending INV-Q-4171) and appends it through
 * outbox-and-queue in the transaction the caller holds. One event carries the whole of both key sets, as the authority
 * replica replaces what it holds; every item carries its `use`. The payload is the
 * `operator-session-keys-changed-payload` schema of platform-contracts, at most [MAX_KEYS] items.
 */
class OperatorSessionKeysChangedOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
) {
    fun publish(valid: List<VerificationKey>) = publishAll(valid.map(PublishedKey::of))

    /** Publishes [valid] of every use; a kid under two uses, or an item without its use, publishes nothing. */
    fun publishAll(valid: List<PublishedKey>) {
        requireOneUsePerKid(valid)
        val context = EventContexts.forAggregate(TenantId.PLATFORM, EntityRef.PLATFORM, AGGREGATE_KEY)
        val payload = payloadOf(valid)
        requireUsesOf(payload, valid)
        outbox.append(listOf(OutboundEvent(EVENT_TYPE, payload.toString())), context)
    }

    /**
     * The channel schema reads an item without `use` as OPERATOR_SESSION and may not make it required inside a major
     * version (API-41), so the publication refuses a payload whose item for a key lacks `use` or names another use.
     */
    internal fun requireUses(
        payload: ObjectNode,
        valid: List<VerificationKey>,
    ) = requireUsesOf(payload, valid.map(PublishedKey::of))

    internal fun requireUsesOf(
        payload: ObjectNode,
        valid: List<PublishedKey>,
    ) {
        valid.forEach { key ->
            val item = payload["keys"].singleOrNull { it["kid"]?.asString() == key.kid }
            check(item != null && item["use"]?.asString() == key.use) {
                "the item of key ${key.kid} lacks use ${key.use}; the event is not published"
            }
        }
    }

    /** A kid is never published under two uses (NIST SP 800-57): nothing is emitted and an error is logged. */
    private fun requireOneUsePerKid(valid: List<PublishedKey>) {
        val shared = valid.groupBy { it.kid }.filterValues { items -> items.map { it.use }.distinct().size > 1 }.keys
        if (shared.isNotEmpty()) {
            log.error("key publication refused: kid {} is held under two uses", shared.sorted())
            error("a kid is held under two uses: ${shared.sorted()}; the event is not published")
        }
    }

    /** True when [recordJson], an earlier record of this event, carries exactly the key set [valid] would publish. */
    fun carries(
        recordJson: String,
        valid: List<VerificationKey>,
    ): Boolean = carriesAll(recordJson, valid.map(PublishedKey::of))

    fun carriesAll(
        recordJson: String,
        valid: List<PublishedKey>,
    ): Boolean = mapper.readTree(recordJson) == payloadOf(valid)

    internal fun payload(valid: List<VerificationKey>): ObjectNode = payloadOf(valid.map(PublishedKey::of))

    internal fun payloadOf(valid: List<PublishedKey>): ObjectNode {
        check(valid.size in 1..MAX_KEYS) {
            "an OperatorSessionKeysChanged carries 1 to $MAX_KEYS keys, not ${valid.size}"
        }
        val root = mapper.createObjectNode()
        val items = root.putArray("keys")
        valid.sortedBy { it.kid }.forEach { key ->
            val node = items.addObject()
            node.put("kid", key.kid)
            node.put("use", key.use)
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
        const val EVENT_TYPE = "com.dynokore.gst.platform.OperatorSessionKeysChanged.v1"
        const val TOPIC = "gst.platform.operator-session-keys-changed.v1"
        const val MAX_KEYS = 6
        private val log = LoggerFactory.getLogger(OperatorSessionKeysChangedOutbox::class.java)
        private const val AGGREGATE_KEY = "operator-session-keys"
        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
