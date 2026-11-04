package com.dynokore.gst.tenancy.adapters.calendar

import com.dynokore.gst.tenancy.application.period.DueDateCause
import com.dynokore.gst.tenancy.application.period.DueDateRecomputation
import com.dynokore.gst.tenancy.application.period.FilingFact
import com.dynokore.gst.tenancy.application.period.FilingFactStore
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** The inbox consumers of the period calendar (one name per topic). */
object ConsumedPeriodEvents {
    const val POLICY_CHANGED_CONSUMER = "tenancy-svc-policy-changed-vp62"
    const val FILING_STATUS_CONSUMER = "tenancy-svc-period-filing-status"
    const val POLICY_CHANGED_TOPIC = "gst.platform.policy-changed.v1"
    const val FILING_STATUS_TOPIC = "gst.submission.period-filing-status-changed.v1"
}

private fun parse(
    mapper: ObjectMapper,
    text: String,
): JsonNode =
    try {
        mapper.readTree(text)
    } catch (e: JacksonException) {
        throw ContractViolation("the payload is not JSON", e)
    }

private fun JsonNode.requiredText(name: String): String =
    this[name]?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
        ?: throw ContractViolation("payload member $name is required")

private fun JsonNode.optionalInstant(name: String): Instant? =
    this[name]?.takeUnless { it.isNull }?.let { node ->
        try {
            Instant.parse(node.takeIf { it.isString }?.asString() ?: throw ContractViolation("$name is not a string"))
        } catch (e: DateTimeParseException) {
            throw ContractViolation("$name is not an instant: ${e.message}")
        }
    }

/**
 * PolicyChanged (INV-Q-16111, INV-AC-16066): an event whose `vpId` equals `vpId` by equality and whose `scope` names
 * the level ENTITY or CLIENT_ENTITY recomputes, in the consumer's transaction, the unfiled periods of the entity the
 * scope names (exact reference) whose filing due date is on or after `effectiveDate`. Anything else is ignored.
 */
class NonBusinessDayPolicyHandler(
    private val recomputation: DueDateRecomputation,
    private val mapper: ObjectMapper,
    private val vpId: String,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(mapper, event.dataJson)
        if (data["vpId"]?.takeIf { it.isString }?.asString() != vpId) return HandleOutcome.IGNORED
        val entityRef = entityOf(data.requiredText("scope"))
        val changes =
            if (entityRef == null) {
                emptyList()
            } else {
                val effective = effectiveDate(data)
                recomputation.applyTo(entityRef, effective, DueDateCause.VariationPoint(vpId, effective))
            }
        return if (changes.isEmpty()) HandleOutcome.IGNORED else HandleOutcome.APPLIED
    }

    /** The entity the scope text `LEVEL:id` names, for the levels ENTITY and CLIENT_ENTITY only (exact match). */
    private fun entityOf(scope: String): String? =
        scope
            .substringAfter(':', "")
            .takeIf { it.isNotEmpty() && scope.substringBefore(':') in ENTITY_LEVELS }

    private fun effectiveDate(data: JsonNode): LocalDate =
        try {
            LocalDate.parse(data.requiredText("effectiveDate"))
        } catch (e: DateTimeParseException) {
            throw ContractViolation("effectiveDate is not a date: ${e.message}")
        }

    private companion object {
        val ENTITY_LEVELS = setOf("ENTITY", "CLIENT_ENTITY")
    }
}

/**
 * PeriodFilingStatusChanged (INV-Q-16113): keeps the event with the greatest `recordedAt` per entity and period. A
 * payload without `entityRef`, `period`, `submissionLate` or `recordedAt` breaks the contract and stores nothing.
 */
class PeriodFilingStatusHandler(
    private val facts: FilingFactStore,
    private val mapper: ObjectMapper,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(mapper, event.dataJson)
        val late = data["submissionLate"]?.takeIf { it.isBoolean }?.asBoolean()
        val recordedAt = data.optionalInstant("recordedAt")
        if (late == null || recordedAt == null) throw ContractViolation("submissionLate and recordedAt are required")
        val fact =
            FilingFact(
                data.requiredText("entityRef"),
                data.requiredText("period"),
                late,
                data.optionalInstant("governingFiledAt"),
                data["conflict"]?.takeIf { it.isBoolean }?.asBoolean() ?: false,
                recordedAt,
            )
        return if (facts.keep(fact)) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
