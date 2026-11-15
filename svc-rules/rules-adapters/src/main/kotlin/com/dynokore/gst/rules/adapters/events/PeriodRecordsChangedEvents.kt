package com.dynokore.gst.rules.adapters.events

import com.dynokore.gst.rules.adapters.persistence.JooqPeriodRecordsStore
import com.dynokore.gst.rules.adapters.persistence.PeriodSpan
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** The event tenancy-svc publishes with the period records of an entity (INV-T-20226, INV-REQ-1303). */
object PeriodRecordsChangedEvents {
    const val TYPE = "com.dynokore.gst.tenancy.PeriodRecordsChanged.v1"
    const val TOPIC = "gst.tenancy.period-records-changed.v1"
    private const val TENANCY = "tenancy-svc"

    /** The entry of the consumer's own catalogue (producer tenancy-svc; the event carries no transaction fact). */
    fun entry() =
        CatalogueEntry(
            eventType = TYPE,
            topic = TOPIC,
            producers = setOf(TENANCY),
            contentTopic = false,
        )
}

/**
 * The inbox handler of `PeriodRecordsChanged`: the complete set of period records in force of an entity, stored in the
 * consumer's transaction with the version. A version not above the stored one is [HandleOutcome.IGNORED]. A payload
 * that breaks the shape is a [ContractViolation] and nothing is stored. Members that are not read are ignored (the
 * payload is additive-only). It writes no outbox row.
 */
class PeriodRecordsChangedHandler(
    private val store: JooqPeriodRecordsStore,
    private val clock: Clock,
    private val mapper: JsonMapper = JsonMapper.builder().build(),
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val entityRef = reference(data.get("entityRef"), "entityRef")
        val versionNode = data.get("version")
        if (versionNode == null || !versionNode.isIntegralNumber || !versionNode.canConvertToLong()) {
            bad("version is not an integer")
        }
        val version = versionNode.asLong()
        if (version < 1) bad("version is below 1")
        val periods = data.get("periods")
        if (periods == null || !periods.isArray) bad("periods is not an array")
        if (periods.size() < 1 || periods.size() > MAX_PERIODS) bad("periods holds not 1 to $MAX_PERIODS items")
        val spans = (0 until periods.size()).map { span(periods.get(it)) }
        if (spans.map { it.periodRef }.toSet().size != spans.size) bad("a periodRef is repeated in the event")
        return if (store.replace(event.tenant, entityRef, version, spans, clock.instant())) {
            HandleOutcome.APPLIED
        } else {
            HandleOutcome.IGNORED
        }
    }

    private fun span(node: JsonNode): PeriodSpan {
        if (!node.isObject) bad("a period record is not an object")
        val start = date(node.get("periodStart"), "periodStart")
        val end = date(node.get("periodEnd"), "periodEnd")
        if (end.isBefore(start)) bad("periodEnd is before periodStart")
        return PeriodSpan(reference(node.get("periodRef"), "periodRef"), start, end)
    }

    private fun reference(
        node: JsonNode?,
        name: String,
    ): String {
        if (node == null || !node.isString) bad("$name is not a string")
        val text = node.asString()
        if (text.isBlank() || text.length > MAX_REF) bad("$name is blank or longer than $MAX_REF characters")
        return text
    }

    private fun date(
        node: JsonNode?,
        name: String,
    ): LocalDate {
        if (node == null || !node.isString) bad("$name is not a string")
        return try {
            LocalDate.parse(node.asString())
        } catch (e: DateTimeParseException) {
            throw ContractViolation("$name is not an ISO date", e)
        }
    }

    private fun parse(text: String): JsonNode =
        try {
            mapper.readTree(text)
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }.also { if (!it.isObject) bad("the payload is not an object") }

    private fun bad(reason: String): Nothing = throw ContractViolation(reason)

    private companion object {
        const val MAX_REF = 64
        const val MAX_PERIODS = 600
    }
}
