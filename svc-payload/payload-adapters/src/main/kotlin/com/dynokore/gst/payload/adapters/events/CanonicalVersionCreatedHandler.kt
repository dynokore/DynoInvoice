package com.dynokore.gst.payload.adapters.events

import com.dynokore.gst.payload.application.build.BuildTrigger
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
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * The narrow port that stores the canonical document of a version with its routing facts (interface segregation: the
 * handler never sees the concrete store).
 */
fun interface CanonicalInputWriter {
    /** True when a row was newly stored; the first stored document of a version wins. */
    fun store(
        docUuid: UUID,
        version: Int,
        canonicalDocument: String,
        direction: String?,
        counterpartyOnNetwork: Boolean?,
        transactionDate: LocalDate? = null,
    ): Boolean
}

/**
 * The consumer of `gst.canonical.canonical-version-created.v1` for payload-svc (INV-Q-17166 A): stores the entity and
 * the SGD total of the version once, stores its canonical document with the routing facts of the event, and asks the
 * [BuildTrigger] to enqueue the build. It runs inside the inbox consumer's transaction. A payload that is not JSON,
 * lacks `docUuid` or `version`, or holds a total outside the canonical pattern is refused with [ContractViolation],
 * which goes to the dead letter.
 */
class CanonicalVersionCreatedHandler(
    private val recordVersionFacts: RecordVersionFacts,
    private val inputs: CanonicalInputWriter,
    private val trigger: BuildTrigger,
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val docUuid = docUuid(data)
        val version = version(data)
        val document = data.get("canonicalDocument")
        if (document == null || !document.isObject) throw ContractViolation("canonicalDocument is not an object")
        val facts = facts(event.entity.value, total(document), aggregateId(data))
        val routing = data.get("routingFacts")
        val direction = RoutingFacts.direction(routing)
        val onNetwork = RoutingFacts.counterpartyOnNetwork(routing)
        val factsStored = recordVersionFacts.record(docUuid, version, facts)
        val inputStored = inputs.store(docUuid, version, json.writeValueAsString(document), direction, onNetwork, transactionDate(data))
        trigger.onInputStored(docUuid, version)
        return if (factsStored || inputStored) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }

    private fun docUuid(data: JsonNode): UUID =
        runCatching { UUID.fromString(text(data, "docUuid")) }
            .getOrElse { throw ContractViolation("docUuid is not a UUID", it) }

    private fun version(data: JsonNode): Int {
        val node = data.required("version")
        return node.takeIf { it.isInt && it.asInt() >= 1 }?.asInt()
            ?: throw ContractViolation("version is not an integer of 1 or more")
    }

    /** The optional `aggregateId`: absent keeps null; anything but a non-empty string is refused. */
    private fun aggregateId(data: JsonNode): String? {
        val node = data.get("aggregateId") ?: return null
        if (!node.isString ||
            node.asString().isBlank()
        ) {
            throw ContractViolation("aggregateId is not a non-empty string")
        }
        return node.asString()
    }

    /** The optional `transactionDate` (ISO date): absent or null is null, never defaulted; a malformed one is refused. */
    private fun transactionDate(data: JsonNode): LocalDate? {
        val node = data.get("transactionDate")?.takeUnless { it.isNull } ?: return null
        if (!node.isString) throw ContractViolation("transactionDate is not text")
        return try {
            LocalDate.parse(node.asString())
        } catch (e: DateTimeParseException) {
            throw ContractViolation("transactionDate is not an ISO date", e)
        }
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
        aggregateId: String?,
    ): VersionFacts =
        try {
            VersionFacts(entityRef, total, aggregateId)
        } catch (e: IllegalArgumentException) {
            throw ContractViolation("entityref or aggregateId is blank", e)
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

/** The optional `routingFacts` member of the event; each refusal is a [ContractViolation]. */
private object RoutingFacts {
    private val DIRECTIONS = setOf("SALE", "PURCHASE")

    /** The optional `routingFacts.direction`: absent keeps null; anything but SALE or PURCHASE text is refused. */
    fun direction(routing: JsonNode?): String? {
        val node = member(routing, "direction") ?: return null
        if (!node.isString || node.asString() !in DIRECTIONS) {
            throw ContractViolation("routingFacts.direction is not SALE or PURCHASE")
        }
        return node.asString()
    }

    /** The optional `routingFacts.counterpartyOnNetwork`: absent stays null, never false; non-boolean is refused. */
    fun counterpartyOnNetwork(routing: JsonNode?): Boolean? {
        val node = member(routing, "counterpartyOnNetwork") ?: return null
        if (!node.isBoolean) throw ContractViolation("routingFacts.counterpartyOnNetwork is not a boolean")
        return node.asBoolean()
    }

    private fun member(
        routing: JsonNode?,
        name: String,
    ): JsonNode? {
        if (routing == null || routing.isNull) return null
        if (!routing.isObject) throw ContractViolation("routingFacts is not an object")
        return routing.get(name)?.takeUnless { it.isNull }
    }
}
