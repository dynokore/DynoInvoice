package com.dynokore.gst.rules.adapters.events

import com.dynokore.gst.rules.adapters.persistence.JooqSamplePageStore
import com.dynokore.gst.rules.application.DryRunWindow
import com.dynokore.gst.rules.application.SamplePage
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/**
 * What the sample request of a dry run carries (platform-contracts `pack-dry-run-sample-requested-payload.schema.json`,
 * INV-Q-4870 B): the dry-run id, the tenant (the envelope's), the entity, the window and both pack versions.
 */
data class DryRunSampleRequest(
    val dryRunId: String,
    val tenant: TenantId,
    val entityRef: String,
    val window: DryRunWindow,
    val currentPackVersion: String,
    val candidatePackVersion: String,
    val correlation: String,
    val causation: String,
)

/**
 * The two platform events of the dry-run sample (INV-T-4915 contract, INV-Q-4995): rules-svc publishes
 * `PackDryRunSampleRequested` through the outbox of the command's open transaction and consumes
 * `PackDryRunSampleRecorded`, which canonical-svc publishes page by page.
 */
class DryRunSampleEvents(
    private val writer: OutboxWriter,
    private val mapper: JsonMapper = JsonMapper.builder().build(),
) {
    /** Appends the request to the outbox in the caller's open transaction; a rolled-back command leaves no row. */
    fun publish(request: DryRunSampleRequest) {
        val payload =
            linkedMapOf(
                "dryRunId" to request.dryRunId,
                "entityRef" to request.entityRef,
                "firstPeriodRef" to request.window.firstPeriodRef,
                "lastPeriodRef" to request.window.lastPeriodRef,
                "periodCount" to request.window.periodCount,
                "currentPackVersion" to request.currentPackVersion,
                "candidatePackVersion" to request.candidatePackVersion,
            )
        writer.append(
            listOf(OutboundEvent(REQUESTED, mapper.writeValueAsString(payload))),
            EventContext(
                tenant = request.tenant,
                entity = EntityRef(request.entityRef),
                docUuid = null,
                transactionDate = null,
                aggregateKey = request.dryRunId,
                aggregateVersion = null,
                correlation = CorrelationId(request.correlation),
                causation = CausationId(request.causation),
            ),
        )
    }

    companion object {
        const val REQUESTED = "com.dynokore.gst.platform.PackDryRunSampleRequested.v1"
        const val RECORDED = "com.dynokore.gst.platform.PackDryRunSampleRecorded.v1"
        const val REQUESTED_TOPIC = "gst.platform.pack-dry-run-sample-requested.v1"
        const val RECORDED_TOPIC = "gst.platform.pack-dry-run-sample-recorded.v1"
        private const val CANONICAL = "canonical-svc"

        /** The request rules-svc publishes (producer rules-svc). */
        fun requestedEntry() =
            CatalogueEntry(
                eventType = REQUESTED,
                topic = REQUESTED_TOPIC,
                producers = setOf(RulesEventCatalogue.SERVICE),
                contentTopic = false,
            )

        /** The pages rules-svc consumes (producer canonical-svc; the pages carry tenant transaction facts). */
        fun recordedEntry() =
            CatalogueEntry(
                eventType = RECORDED,
                topic = RECORDED_TOPIC,
                producers = setOf(CANONICAL),
                contentTopic = true,
            )
    }
}

/**
 * The inbox handler of `PackDryRunSampleRecorded`: stores the page in the consumer's transaction, so the page rows, the
 * inbox row and the offset commit together. A page redelivered under the same tenant, dry-run id and page number is
 * stored once ([HandleOutcome.IGNORED]). The members of the items are stored as received and validated by nobody here
 * (INV-T-19903): a payload that breaks the shape of the page itself is a [ContractViolation] and is dead-lettered.
 */
class DryRunSampleRecordedHandler(
    private val store: JooqSamplePageStore,
    private val clock: Clock,
    private val mapper: JsonMapper = JsonMapper.builder().build(),
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val dryRunId = data.required("dryRunId").takeIf { it.isString }?.asString() ?: bad("dryRunId is not a string")
        if (dryRunId.isBlank() || dryRunId.length > MAX_RUN_ID) bad("dryRunId is blank or too long")
        val number = data.required("pageNumber").takeIf { it.isInt && it.asInt() >= 1 }?.asInt() ?: bad("pageNumber")
        val last = data.required("lastPage").takeIf { it.isBoolean }?.asBoolean() ?: bad("lastPage is not a boolean")
        val documents = data.required("documents").takeIf { it.isArray } ?: bad("documents is not an array")
        if (documents.size() > MAX_ITEMS) bad("documents holds more than $MAX_ITEMS items")
        val page = SamplePage(number, last, (0 until documents.size()).map { item(documents.get(it)) })
        return if (store.record(event.tenant, dryRunId, page, clock.instant())) {
            HandleOutcome.APPLIED
        } else {
            HandleOutcome.IGNORED
        }
    }

    /** The members of one item as the producer sent them: no member is read, converted or corrected. */
    private fun item(node: JsonNode): Map<String, Any?> {
        if (!node.isObject) bad("a page item is not an object")
        return node.properties().associate { (name, value) -> name to member(value) }
    }

    private fun member(value: JsonNode): Any? =
        when {
            value.isNull -> null
            value.isString -> value.asString()
            value.isBoolean -> value.asBoolean()
            value.isNumber -> value.numberValue()
            else -> value.toString()
        }

    private fun parse(text: String): JsonNode =
        try {
            mapper.readTree(text)
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private fun JsonNode.required(name: String): JsonNode =
        get(name)?.takeUnless { it.isNull } ?: bad("the payload has no $name")

    private fun bad(reason: String): Nothing = throw ContractViolation(reason)

    private companion object {
        const val MAX_RUN_ID = 40
        const val MAX_ITEMS = 500
    }
}
