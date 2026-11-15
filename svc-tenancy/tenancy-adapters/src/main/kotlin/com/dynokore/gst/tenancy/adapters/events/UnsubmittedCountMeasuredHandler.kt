package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** The unsubmitted in-scope documents of one entity; [count] is at least 1. */
data class EntityUnsubmittedCount(
    val entityRef: String,
    val count: Int,
)

/** The state of the count of one tenant at [measuredAt]; [total] is the sum of the entity counts. */
data class UnsubmittedCountMeasurement(
    val measuredAt: Instant,
    val total: Int,
    val entities: List<EntityUnsubmittedCount>,
)

/** The read model of the count, one row per tenant and envelope entity (the producer writes the tenant-wide one). */
interface UnsubmittedCountReplica {
    /**
     * Stores [measurement] for [tenant] and [entity] with [receivedAt] when no row exists or the stored `measuredAt` is strictly
     * smaller; true when one row changed. An equal or older measurement changes nothing, `receivedAt` included.
     */
    fun keep(
        tenant: TenantId,
        entity: EntityRef,
        measurement: UnsubmittedCountMeasurement,
        receivedAt: Instant,
    ): Boolean
}

/**
 * The consumer of `gst.submission.unsubmitted-document-count-measured.v1` (INV-REQ-956, INV-AC-23029): keeps, per
 * tenant, the event with the greatest `measuredAt` and stamps the receipt time from [clock] only when it applies one.
 * An envelope with a real entity, or a payload that breaks the contract, is a [ContractViolation] and stores nothing; the total must equal the sum of the
 * item counts (the producer guarantees it, so a difference is never repaired). The consumer emits no event.
 */
class UnsubmittedCountMeasuredHandler(
    private val replica: UnsubmittedCountReplica,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        if (!event.entity.isTenantWide) throw ContractViolation("the envelope entity is not the tenant-wide entity")
        val data = parse(event.dataJson)
        val measurement = measurement(data)
        return if (replica.keep(
                event.tenant,
                event.entity,
                measurement,
                clock.instant(),
            )
        ) {
            HandleOutcome.APPLIED
        } else {
            HandleOutcome.IGNORED
        }
    }

    private fun measurement(data: JsonNode): UnsubmittedCountMeasurement {
        val measuredAt = measuredAt(data["measuredAt"])
        val total = count(data["unsubmittedDocumentCount"], "unsubmittedDocumentCount", 0)
        val items =
            data["entities"]?.takeIf { it.isArray } ?: throw ContractViolation("entities is missing or not an array")
        val entities = items.values().map { item -> entity(item) }
        requireConsistent(entities, total)
        return UnsubmittedCountMeasurement(measuredAt, total, entities)
    }

    private fun requireConsistent(
        entities: List<EntityUnsubmittedCount>,
        total: Int,
    ) {
        if (entities.map { it.entityRef }.toSet().size != entities.size) throw ContractViolation("an entityRef repeats")
        if (entities.sumOf { it.count.toLong() } != total.toLong()) {
            throw ContractViolation("the total differs from the sum of the item counts")
        }
    }

    private fun entity(item: JsonNode): EntityUnsubmittedCount {
        if (!item.isObject) throw ContractViolation("an entities item is not an object")
        val ref =
            item["entityRef"]?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
                ?: throw ContractViolation("an item has no entityRef")
        return EntityUnsubmittedCount(ref, count(item["unsubmittedDocumentCount"], "an item count", 1))
    }

    private fun count(
        node: JsonNode?,
        name: String,
        minimum: Int,
    ): Int {
        if (node == null || !node.isIntegralNumber || !node.canConvertToInt()) {
            throw ContractViolation("$name is missing or not an integer")
        }
        return node.asInt().also { if (it < minimum) throw ContractViolation("$name is below $minimum") }
    }

    private fun measuredAt(node: JsonNode?): Instant {
        val text = node?.takeIf { it.isString }?.asString() ?: throw ContractViolation("measuredAt is missing")
        return try {
            OffsetDateTime.parse(text).toInstant()
        } catch (e: DateTimeParseException) {
            throw ContractViolation("measuredAt is not a date-time", e)
        }
    }

    private fun parse(text: String): JsonNode =
        try {
            mapper.readTree(text)
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }
}
