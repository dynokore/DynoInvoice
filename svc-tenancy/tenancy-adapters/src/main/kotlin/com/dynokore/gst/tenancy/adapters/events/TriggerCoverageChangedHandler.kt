package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TRIGGER_COVERAGE_REPLICA
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import org.jooq.DSLContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * The consumer of `gst.ingestion.trigger-coverage-changed.v1` (INV-REQ-788, INV-AC-20941): upserts the replica row of
 * (tenant, entity, trigger class) on the tenant-bound [DSLContext] only when `changedAt` is newer than the stored one,
 * so a redelivery or an older event changes nothing. A payload that breaks the contract is a [ContractViolation] and
 * ends in the dead letter. The consumer emits no event.
 */
class TriggerCoverageChangedHandler(
    private val dsl: DSLContext,
) : EventHandler {
    private val json = JsonMapper.builder().build()

    @Suppress("CyclomaticComplexMethod")
    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val entityRef = required(data, "entityRef")
        val triggerClass = required(data, "triggerClass")
        val status = required(data, "status")
        val changedAt = changedAt(required(data, "changedAt"))
        if (triggerClass !in TRIGGER_CLASSES) throw ContractViolation("triggerClass is not one of the five classes")
        if (status !in STATUSES) throw ContractViolation("status is not one of the three statuses")
        val adapterRef = optional(data, "adapterRef")
        val reviewDate = optional(data, "reviewDate")?.let(::date)
        val coveringSolution = optional(data, "coveringSolution")
        val evidenceReference = optional(data, "evidenceReference")
        val actorUserId = optional(data, "actorUserId")
        val t = TRIGGER_COVERAGE_REPLICA
        val changed =
            dsl
                .insertInto(t)
                .set(t.TENANT_ID, event.tenant.value)
                .set(t.ENTITY_REF, entityRef)
                .set(t.TRIGGER_CLASS, triggerClass)
                .set(t.STATUS, status)
                .set(t.ADAPTER_REF, adapterRef)
                .set(t.REVIEW_DATE, reviewDate)
                .set(t.COVERING_SOLUTION, coveringSolution)
                .set(t.EVIDENCE_REFERENCE, evidenceReference)
                .set(t.CHANGED_AT, changedAt)
                .set(t.ACTOR_USER_ID, actorUserId)
                .onConflict(t.TENANT_ID, t.ENTITY_REF, t.TRIGGER_CLASS)
                .doUpdate()
                .set(t.STATUS, status)
                .set(t.ADAPTER_REF, adapterRef)
                .set(t.REVIEW_DATE, reviewDate)
                .set(t.COVERING_SOLUTION, coveringSolution)
                .set(t.EVIDENCE_REFERENCE, evidenceReference)
                .set(t.CHANGED_AT, changedAt)
                .set(t.ACTOR_USER_ID, actorUserId)
                .where(t.CHANGED_AT.lt(changedAt))
                .execute()
        return if (changed == 1) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text)
        } catch (e: tools.jackson.core.JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private fun required(
        data: JsonNode,
        name: String,
    ): String = optional(data, name) ?: throw ContractViolation("the payload has no $name")

    private fun optional(
        data: JsonNode,
        name: String,
    ): String? {
        val node = data.get(name)?.takeUnless { it.isNull } ?: return null
        val value = if (node.isString) node.asString() else null
        return value?.takeUnless { it.isBlank() } ?: throw ContractViolation("$name is not a non-blank string")
    }

    private fun changedAt(text: String): OffsetDateTime =
        try {
            OffsetDateTime.parse(text)
        } catch (e: DateTimeParseException) {
            throw ContractViolation("changedAt is not a date-time", e)
        }

    private fun date(text: String): LocalDate =
        try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            throw ContractViolation("reviewDate is not a date", e)
        }

    private companion object {
        val TRIGGER_CLASSES =
            setOf(
                "OUTBOUND_DOCUMENT_ISSUED",
                "INBOUND_DOCUMENT_RECORDED",
                "AGGREGABLE_BATCH_CLOSED",
                "JOURNAL_ONLY_SUPPLY",
                "THIRD_PARTY_ISSUANCE",
            )
        val STATUSES = setOf("MAPPED", "WAIVED", "COVERED_BY_OTHER_SOLUTION")
    }
}
