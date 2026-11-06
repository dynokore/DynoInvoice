package com.dynokore.gst.payload.adapters.events

import com.dynokore.gst.payload.application.version.RecordVersionFacts
import com.dynokore.gst.payload.domain.version.VersionFacts
import com.dynokore.gst.payload.domain.version.totalSgdIncludingTax
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.util.UUID

/**
 * The consumer of `gst.canonical.canonical-version-created.v1` for payload-svc (INV-Q-17166 A): stores the entity and
 * the SGD total of the version once. It runs inside the inbox consumer's transaction. A payload that is not JSON,
 * lacks `docUuid` or `version`, or holds a total outside the canonical pattern is refused with [ContractViolation],
 * which goes to the dead letter.
 */
class CanonicalVersionCreatedHandler(
    private val recordVersionFacts: RecordVersionFacts,
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val docUuid = docUuid(data)
        val version = version(data)
        val facts = facts(event.entity.value, total(data.path("canonicalDocument")))
        return if (recordVersionFacts.record(docUuid, version, facts)) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }

    private fun docUuid(data: JsonNode): UUID =
        runCatching { UUID.fromString(text(data, "docUuid")) }
            .getOrElse { throw ContractViolation("docUuid is not a UUID", it) }

    private fun version(data: JsonNode): Int {
        val node = data.required("version")
        return node.takeIf { it.isInt && it.asInt() >= 1 }?.asInt()
            ?: throw ContractViolation("version is not an integer of 1 or more")
    }

    private fun total(document: JsonNode): BigDecimal? {
        val totals = document.path("totals").path("value")
        return try {
            totalSgdIncludingTax(
                currency = optionalText(document.path("currency").path("value")),
                totalIncludingTax = optionalText(totals.path("totalIncludingTax")),
                sgdTotalIncludingTax = optionalText(totals.path("sgdTotalIncludingTax")),
            )
        } catch (e: IllegalArgumentException) {
            throw ContractViolation("the total is outside the canonical pattern", e)
        }
    }

    private fun facts(
        entityRef: String,
        total: BigDecimal?,
    ): VersionFacts =
        try {
            VersionFacts(entityRef, total)
        } catch (e: IllegalArgumentException) {
            throw ContractViolation("entityref is blank", e)
        }

    private fun optionalText(node: JsonNode): String? =
        when {
            node.isMissingNode || node.isNull -> null
            node.isString -> node.asString()
            else -> throw ContractViolation("a currency or total is not text")
        }

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text)
        } catch (e: tools.jackson.core.JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private fun JsonNode.required(name: String): JsonNode =
        get(name)?.takeUnless { it.isNull } ?: throw ContractViolation("the payload has no $name")

    private fun text(
        data: JsonNode,
        name: String,
    ): String {
        val node = data.required(name)
        if (!node.isString) throw ContractViolation("$name is not text")
        return node.asString().also { if (it.isBlank()) throw ContractViolation("$name is blank") }
    }
}
