package com.dynokore.gst.aggregation.adapters.periods

import com.dynokore.gst.aggregation.domain.PeriodSpan
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** The one event type and topic this service consumes for the period spans (INV-Q-16535 A). */
object ConsumedPeriodEvents {
    const val PERIOD_RECORDS_CHANGED = "com.dynokore.gst.tenancy.PeriodRecordsChanged.v1"
    const val PERIOD_RECORDS_CHANGED_TOPIC = "gst.tenancy.period-records-changed.v1"
}

/**
 * PeriodRecordsChanged (event-carried state transfer, idempotent consumer by version): replaces the whole set of the
 * entity when the version rises, else ignores the event. A payload that breaks the contract is a [ContractViolation]
 * and stores nothing.
 */
class PeriodRecordsChangedHandler(
    private val spans: JooqPeriodSpanStore,
    private val mapper: ObjectMapper,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        require(event.type == ConsumedPeriodEvents.PERIOD_RECORDS_CHANGED) { "unexpected ${event.type}" }
        val data = mapper.readTree(event.dataJson)
        val entityRef = data.text("entityRef")
        val version = data["version"]?.takeIf { it.isIntegralNumber }?.asLong() ?: throw ContractViolation("version")
        if (version < 1) throw ContractViolation("version must be at least 1")
        val items = data["periods"]?.takeIf { it.isArray } ?: throw ContractViolation("periods is required")
        val periods = items.values().map { period(it) }
        if (periods.map { it.periodRef }.toSet().size != periods.size) throw ContractViolation("periodRef repeats")
        return if (spans.replace(entityRef, version, periods)) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }

    private fun period(node: JsonNode): PeriodSpan {
        val start = node.date("periodStart")
        val end = node.date("periodEnd")
        if (end.isBefore(start)) throw ContractViolation("periodEnd is before periodStart")
        return PeriodSpan(start, end, node.text("periodRef"))
    }

    private fun JsonNode.text(name: String): String =
        this[name]?.takeIf { it.isString }?.asString()?.takeIf { it.isNotEmpty() }
            ?: throw ContractViolation("payload member $name is required")

    private fun JsonNode.date(name: String): LocalDate =
        try {
            LocalDate.parse(text(name))
        } catch (bad: DateTimeParseException) {
            throw ContractViolation("payload member $name is not a date: ${bad.message}")
        }
}
