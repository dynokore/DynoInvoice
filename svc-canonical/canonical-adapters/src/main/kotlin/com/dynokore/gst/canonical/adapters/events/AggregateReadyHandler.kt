package com.dynokore.gst.canonical.adapters.events

import com.dynokore.gst.canonical.application.aggregate.AggregateDirection
import com.dynokore.gst.canonical.application.aggregate.AggregateInput
import com.dynokore.gst.canonical.application.aggregate.AggregateLine
import com.dynokore.gst.canonical.application.aggregate.AggregateRefused
import com.dynokore.gst.canonical.application.aggregate.RecordAggregateDocument
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The consumer of `gst.aggregation.aggregate-ready.v1` for canonical-svc (INV-REQ-437, INV-REQ-718): it reads the
 * payload through the JSON tree and calls [RecordAggregateDocument], which runs inside the inbox consumer's
 * transaction.
 *
 * Malformed input is a [ContractViolation] (dead letter at once): a payload that is not a JSON object, a missing or
 * blank required member, an unknown aggregate type, a window date that is not a date, a line amount or taxable amount
 * that is not a decimal string, a flag that is not a boolean, and an [AggregateRefused] of the use case. Any other
 * exception, [com.dynokore.gst.canonical.application.aggregate.AggregateRetry] included, propagates unchanged: the
 * record rolls back and is retried.
 */
class AggregateReadyHandler(
    private val record: RecordAggregateDocument,
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        val input = read(event)
        val result =
            try {
                record.record(input)
            } catch (refused: AggregateRefused) {
                throw ContractViolation(refused.message ?: "the aggregate is refused", refused)
            }
        return if (result.inserted) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }

    private fun read(event: Envelope): AggregateInput {
        if (event.tenant.isPlatform) throw ContractViolation("the envelope carries no tenantid")
        val data = parse(event.dataJson)
        val aggregateType = required(data, AGGREGATE_TYPE)
        try {
            AggregateDirection.of(aggregateType)
        } catch (e: IllegalArgumentException) {
            throw ContractViolation("$AGGREGATE_TYPE is not POS, STI or PCP", e)
        }
        val window = data.get(WINDOW)?.takeUnless { it.isNull }
        if (window != null && !window.isObject) throw ContractViolation("$WINDOW is not an object")
        flag(data, IS_SUPPLEMENTARY)
        window?.let { optionalDate(it, FROM) }
        return AggregateInput(
            tenantId = event.tenant.value,
            aggregateId = required(data, AGGREGATE_ID),
            entityRef = required(data, ENTITY_REF),
            aggregateType = aggregateType,
            periodRef = window?.let { optionalText(it, PERIOD_REF) },
            windowTo = window?.let { optionalDate(it, TO) },
            isCreditNote = flag(data, IS_CREDIT_NOTE),
            lines = lines(data),
        )
    }

    private fun lines(data: JsonNode): List<AggregateLine> {
        val node = data.get(LINES)
        if (node == null || !node.isArray) throw ContractViolation("$LINES is missing or not an array")
        return buildList {
            for (line in node) {
                if (!line.isObject) throw ContractViolation("$LINES holds an element that is not an object")
                add(
                    AggregateLine(
                        internalTaxCode = required(line, INTERNAL_TAX_CODE),
                        amount = decimal(line, AMOUNT) ?: throw ContractViolation("$AMOUNT is missing"),
                        taxableAmount = decimal(line, TAXABLE_AMOUNT),
                    ),
                )
            }
        }
    }

    private fun decimal(
        node: JsonNode,
        member: String,
    ): BigDecimal? {
        val value = node.get(member)
        if (value == null || value.isNull) return null
        if (!value.isString || !DECIMAL.matches(value.asString())) {
            throw ContractViolation("$member is not a decimal string")
        }
        return BigDecimal(value.asString())
    }

    private fun required(
        node: JsonNode,
        member: String,
    ): String =
        node.get(member)?.takeIf { it.isString && it.asString().isNotBlank() }?.asString()
            ?: throw ContractViolation("$member is missing or not a non-blank text")

    private fun optionalText(
        node: JsonNode,
        member: String,
    ): String? {
        val value = node.get(member)
        if (value == null || value.isNull) return null
        if (!value.isString) throw ContractViolation("$member is not a text")
        return value.asString()
    }

    private fun optionalDate(
        node: JsonNode,
        member: String,
    ): LocalDate? {
        val text = optionalText(node, member) ?: return null
        return try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            throw ContractViolation("$member is not a date", e)
        }
    }

    private fun flag(
        node: JsonNode,
        member: String,
    ): Boolean {
        val value = node.get(member)
        if (value == null || value.isNull) return false
        if (!value.isBoolean) throw ContractViolation("$member is not a boolean")
        return value.asBoolean()
    }

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text).also { if (!it.isObject) throw ContractViolation("the payload is not a JSON object") }
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private companion object {
        const val AGGREGATE_ID = "aggregateId"
        const val ENTITY_REF = "entityRef"
        const val AGGREGATE_TYPE = "aggregateType"
        const val WINDOW = "window"
        const val PERIOD_REF = "periodRef"
        const val FROM = "from"
        const val TO = "to"
        const val LINES = "linesByInternalTaxCode"
        const val INTERNAL_TAX_CODE = "internalTaxCode"
        const val AMOUNT = "amount"
        const val TAXABLE_AMOUNT = "taxableAmount"
        const val IS_CREDIT_NOTE = "isCreditNote"
        const val IS_SUPPLEMENTARY = "isSupplementary"
        val DECIMAL = Regex("^-?[0-9]+(\\.[0-9]+)?$")
    }
}
