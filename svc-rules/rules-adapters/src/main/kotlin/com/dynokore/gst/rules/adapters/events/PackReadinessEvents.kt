package com.dynokore.gst.rules.adapters.events

import com.dynokore.gst.rules.adapters.persistence.JooqPackArmingStore
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** The platform event rules-svc consumes from the instances that hold a staged pack (INV-REQ-1166, INV-REQ-1226). */
object PackReadinessEvents {
    const val ACKNOWLEDGED = "com.dynokore.gst.platform.PackReadinessAcknowledged.v1"
    const val ACKNOWLEDGED_TOPIC = "gst.platform.pack-readiness-acknowledged.v1"
    private val PRODUCERS = setOf("notification-svc", "payload-svc", "reconciliation-svc", "tenancy-svc")

    /** The answers rules-svc consumes (producers as the pinned contract's topics.yaml lists them). */
    fun acknowledgedEntry() =
        CatalogueEntry(
            eventType = ACKNOWLEDGED,
            topic = ACKNOWLEDGED_TOPIC,
            producers = PRODUCERS,
            contentTopic = false,
        )
}

/**
 * The inbox handler of `PackReadinessAcknowledged`: stores the answer in the consumer's transaction, so the answer, the
 * inbox row and the offset commit together. The fields are those of the contract's payload schema: `packIdentifier`
 * (the pack type), `version`, `instance`, `outcome` (LOADED or FAILED) and, for FAILED, `failureReason`. A payload that
 * lacks a required field, or holds one of the wrong type or value, is a [ContractViolation]: nothing is stored. The
 * latest answer of an instance wins; the order is the envelope's `time`, and an answer older than the stored one
 * changes nothing ([HandleOutcome.IGNORED]); an answer of the same time keeps the stored row. The reason text is
 * never a ground for refusing an answer.
 */
class PackReadinessAcknowledgedHandler(
    private val store: JooqPackArmingStore,
    private val mapper: JsonMapper = JsonMapper.builder().build(),
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val packType = text(data, "packIdentifier")
        val version = text(data, "version")
        val instance = text(data, "instance")
        val outcome = text(data, "outcome")
        if (outcome != LOADED && outcome != FAILED) bad("outcome is neither LOADED nor FAILED")
        val reason = data.get("failureReason")?.takeUnless { it.isNull }?.let { optionalText(it) }
        val stored =
            store.recordReadiness(packType, version, instance, outcome, reason.takeIf { outcome == FAILED }, event.time)
        return if (stored) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }

    private fun text(
        data: JsonNode,
        name: String,
    ): String {
        val node = data.get(name)?.takeUnless { it.isNull } ?: bad("the payload has no $name")
        if (!node.isString) bad("$name is not a string")
        return node.asString().also { if (it.isBlank() || it.length > MAX_TEXT) bad("$name is blank or too long") }
    }

    private fun optionalText(node: JsonNode): String {
        if (!node.isString) bad("failureReason is not a string")
        // the reason text is stored verbatim and never a ground for refusing the answer
        return node.asString()
    }

    private fun parse(text: String): JsonNode =
        try {
            mapper.readTree(text)
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private fun bad(reason: String): Nothing = throw ContractViolation(reason)

    private companion object {
        const val LOADED = "LOADED"
        const val FAILED = "FAILED"
        const val MAX_TEXT = 1000
    }
}
