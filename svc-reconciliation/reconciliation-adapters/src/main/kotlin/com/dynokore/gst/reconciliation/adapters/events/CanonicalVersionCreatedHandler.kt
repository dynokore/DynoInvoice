package com.dynokore.gst.reconciliation.adapters.events

import com.dynokore.gst.reconciliation.application.run.DocumentLineFacts
import com.dynokore.gst.reconciliation.application.run.DocumentVersionFacts
import com.dynokore.gst.reconciliation.application.run.RunReadModelWriter
import com.dynokore.gst.reconciliation.domain.boxes.Direction
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The consumer of `gst.canonical.canonical-version-created.v1` for the run read model (INV-REQ-1206, INV-REQ-1221): it
 * hands the facts of the version to the [RunReadModelWriter] inside the inbox consumer's transaction (INV-REQ-1222) and
 * opens none itself. A payload that lacks a required field, an amount that is not a decimal string, a `version` below 1
 * or a blank `prescribedPeriodRef` is a [ContractViolation] (dead letter, nothing stored). An absent `routingFacts`
 * reads as SALE (ruling INV-Q-7239 A); a line without `taxCode` keeps the empty tax code so the run reports it as
 * unmapped and never drops it.
 */
class CanonicalVersionCreatedHandler(
    private val writer: RunReadModelWriter,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = PayloadReading.parse(event.dataJson)
        val document = data.need("canonicalDocument")
        val facts =
            DocumentVersionFacts(
                docUuid = PayloadReading.uuid(data, "docUuid"),
                version = PayloadReading.positiveInt(data, "version"),
                docsequence = PayloadReading.positiveLong(data, "docsequence"),
                periodRef = PayloadReading.text(data, "prescribedPeriodRef"),
                transactionDate = transactionDate(data),
                scopeVerdict = PayloadReading.text(data, "scopeVerdict"),
                notSubmittableReason = notSubmittableReason(data),
                direction = direction(data),
                currency = wrapped(document, "currency").let { PayloadReading.nonBlank(it, "currency") },
                exchangeRate = optionalWrappedDecimal(document, "exchangeRate"),
                sgdTotalExcludingTax = sgdTotal(document, "sgdTotalExcludingTax"),
                sgdTotalTax = sgdTotal(document, "sgdTotalTax"),
                lines = lines(document),
            )
        return if (writer.recordVersion(event.tenant.toString(), event.entity.value, facts)) {
            HandleOutcome.APPLIED
        } else {
            HandleOutcome.IGNORED
        }
    }

    private fun transactionDate(data: JsonNode): LocalDate =
        runCatching { LocalDate.parse(PayloadReading.text(data, "transactionDate")) }
            .getOrElse { throw ContractViolation("transactionDate is not a date", it) }

    private fun notSubmittableReason(data: JsonNode): String? {
        val node = data.get("notSubmittableReason")
        if (node == null || node.isNull) return null
        if (!node.isString) throw ContractViolation("notSubmittableReason is not a string")
        return node.asString()
    }

    /** Absent `routingFacts` reads as SALE; a present object must carry SALE or PURCHASE (INV-Q-7239 A). */
    private fun direction(data: JsonNode): Direction {
        val facts = data.get("routingFacts")
        if (facts == null || facts.isNull) return Direction.SALE
        if (!facts.isObject) throw ContractViolation("routingFacts is not an object")
        val node = facts.get("direction")
        if (node == null || !node.isString) throw ContractViolation("routingFacts.direction is missing")
        return when (node.asString()) {
            "SALE" -> Direction.SALE
            "PURCHASE" -> Direction.PURCHASE
            else -> throw ContractViolation("routingFacts.direction is neither SALE nor PURCHASE")
        }
    }

    /** The `value` of a classification-wrapped field of the canonical document. */
    private fun wrapped(
        document: JsonNode,
        name: String,
    ): JsonNode = document.need(name).need("value")

    private fun optionalWrapped(
        document: JsonNode,
        name: String,
    ): JsonNode? {
        val holder = document.get(name)
        if (holder == null || holder.isNull) return null
        return holder.get("value")?.takeUnless { it.isNull }
    }

    private fun optionalWrappedDecimal(
        document: JsonNode,
        name: String,
    ): BigDecimal? = optionalWrapped(document, name)?.let { PayloadReading.decimal(it, name) }

    private fun sgdTotal(
        document: JsonNode,
        name: String,
    ): BigDecimal? {
        val totals = optionalWrapped(document, "totals") ?: return null
        val node = totals.get(name)
        if (node == null || node.isNull) return null
        return PayloadReading.decimal(node, name)
    }

    private fun lines(document: JsonNode): List<DocumentLineFacts> {
        val array = wrapped(document, "lines")
        if (!array.isArray) throw ContractViolation("canonicalDocument.lines.value is not an array")
        return array.toList().map { line ->
            val taxCode = line.get("taxCode")
            DocumentLineFacts(
                lineNumber = PayloadReading.positiveInt(line, "lineNumber"),
                taxCode =
                    when {
                        taxCode == null || taxCode.isNull -> ""
                        taxCode.isString -> taxCode.asString()
                        else -> throw ContractViolation("taxCode is not a string")
                    },
                lineAmount = PayloadReading.decimal(line.need("lineAmount"), "lineAmount"),
                taxAmount = PayloadReading.decimal(line.need("taxAmount"), "taxAmount"),
            )
        }
    }
}

internal fun JsonNode.need(name: String): JsonNode =
    get(name)?.takeUnless { it.isNull } ?: throw ContractViolation("the payload has no $name")

/** The field reads the three run read model handlers share; every failure is a [ContractViolation]. */
internal object PayloadReading {
    private val json = JsonMapper.builder().build()

    fun parse(text: String): JsonNode {
        val node =
            try {
                json.readTree(text)
            } catch (e: JacksonException) {
                throw ContractViolation("the payload is not JSON", e)
            }
        if (node == null || !node.isObject) throw ContractViolation("the payload is not a JSON object")
        return node
    }

    fun nonBlank(
        node: JsonNode,
        name: String,
    ): String {
        if (!node.isString) throw ContractViolation("$name is not a string")
        return node.asString().also { if (it.isBlank()) throw ContractViolation("$name is blank") }
    }

    fun text(
        data: JsonNode,
        name: String,
    ): String = nonBlank(data.need(name), name)

    fun uuid(
        data: JsonNode,
        name: String,
    ): String =
        runCatching { UUID.fromString(text(data, name)).toString() }
            .getOrElse { throw ContractViolation("$name is not a UUID", it) }

    fun positiveInt(
        data: JsonNode,
        name: String,
    ): Int {
        val node = data.need(name)
        if (!node.isIntegralNumber || !node.canConvertToInt() || node.asInt() < 1) {
            throw ContractViolation("$name is not an integer of at least 1")
        }
        return node.asInt()
    }

    fun positiveLong(
        data: JsonNode,
        name: String,
    ): Long {
        val node = data.need(name)
        if (!node.isIntegralNumber || !node.canConvertToLong() || node.asLong() < 1) {
            throw ContractViolation("$name is not an integer of at least 1")
        }
        return node.asLong()
    }

    /** An amount is a decimal string, parsed as [BigDecimal], never through a binary float. */
    fun decimal(
        node: JsonNode,
        name: String,
    ): BigDecimal {
        if (!node.isString) throw ContractViolation("$name is not a decimal string")
        return runCatching { BigDecimal(node.asString()) }
            .getOrElse { throw ContractViolation("$name is not a decimal", it) }
    }
}
