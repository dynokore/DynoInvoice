package com.dynokore.gst.rules.adapters.events

import com.dynokore.gst.rules.adapters.persistence.JooqPackCorrectionStore
import com.dynokore.gst.rules.adapters.persistence.JooqReprocessingStore
import com.dynokore.gst.rules.application.PackEvaluationPort
import com.dynokore.gst.rules.application.PopulationPage
import com.dynokore.gst.rules.application.PopulationRequestPort
import com.dynokore.gst.rules.application.ReprocessPopulation
import com.dynokore.gst.rules.application.ReprocessingQueuePort
import com.dynokore.gst.rules.application.ReprocessingWorkItem
import com.dynokore.gst.rules.application.SupersedingPackPort
import com.dynokore.gst.rules.domain.dryrun.DocumentOutcome
import com.dynokore.gst.rules.domain.dryrun.SampledDocument
import com.dynokore.gst.rules.domain.pack.PackCorrection
import com.dynokore.gst.rules.domain.reprocessing.PopulationItem
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ClaimedItem
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.QueueEntry
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import org.slf4j.LoggerFactory
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The two platform events of a pack reprocessing (INV-T-24001 contract, producer of the pages INV-T-24003; INV-REQ-1003):
 * rules-svc publishes `PackReprocessingRequested` per entity through the outbox of the command's open transaction and
 * consumes `PackReprocessingPopulationRecorded` pages. rules-svc keeps no standing store of tenant transaction facts
 * (INV-REQ-1196): the facts of a document live only in its work-queue item until it is processed.
 */
class ReprocessingEvents(
    private val writer: OutboxWriter,
    private val runs: JooqReprocessingStore,
    private val correction: JooqPackCorrectionStore,
    private val tenants: TenantContext,
    private val mapper: JsonMapper = JsonMapper.builder().build(),
) : PopulationRequestPort {
    /** Appends the request to the outbox in the caller's open transaction; a rolled-back command leaves no row. */
    override fun request(
        packType: String,
        version: String,
        entityRef: String,
    ) {
        val reprocessingId = checkNotNull(runs.reprocessingIdOf(packType, version)) { "no run for $packType $version" }
        val effectiveFrom = checkNotNull(correction.effectiveFromOf(packType, version)) { "no pack row for $version" }
        val payload =
            linkedMapOf(
                "reprocessingId" to reprocessingId,
                "entityRef" to entityRef,
                "packType" to packType,
                "badPackVersion" to version.removeSuffix(PackCorrection.SUFFIX),
                "supersedingPackVersion" to version,
                "effectiveFrom" to effectiveFrom.toString(),
            )
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        writer.append(
            listOf(OutboundEvent(REQUESTED, mapper.writeValueAsString(payload))),
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(entityRef),
                docUuid = null,
                transactionDate = null,
                aggregateKey = reprocessingId,
                aggregateVersion = null,
                correlation = CorrelationId(correlation),
                causation = CausationId(causation),
            ),
        )
    }

    companion object {
        const val REQUESTED = "com.dynokore.gst.platform.PackReprocessingRequested.v1"
        const val RECORDED = "com.dynokore.gst.platform.PackReprocessingPopulationRecorded.v1"
        const val REQUESTED_TOPIC = "gst.platform.pack-reprocessing-requested.v1"
        const val RECORDED_TOPIC = "gst.platform.pack-reprocessing-population-recorded.v1"
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

/** The facts of the documents of the page or the item in hand, for [SupersedingPackPort.verdict] and the queue port. */
object ItemDocuments {
    private val current = ThreadLocal<Map<String, SampledDocument>>()

    fun <T> with(
        documents: Map<String, SampledDocument>,
        block: () -> T,
    ): T {
        val previous = current.get()
        current.set(documents)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    fun of(docUuid: String): SampledDocument =
        checkNotNull(current.get()?.get(docUuid)) { "no document facts are bound for $docUuid" }
}

/** Evaluates an item under the superseding pack through the evaluation port the dry run uses (INV-T-4978). */
class EvaluatingSupersedingPackPort(
    private val correction: JooqPackCorrectionStore,
    private val evaluation: PackEvaluationPort,
    private val tenants: TenantContext,
) : SupersedingPackPort {
    override fun isArmed(
        packType: String,
        version: String,
    ): Boolean = correction.isArmed(packType, version)

    override fun verdict(
        packType: String,
        version: String,
        item: PopulationItem,
    ): DocumentOutcome = evaluation.evaluate(tenants.current(), packType, version, ItemDocuments.of(item.docUuid))
}

/** Puts one item on the queue lane the use case names, payload the run key and the page item with its facts. */
class QueueingReprocessingPort(
    private val queue: WorkQueue,
    private val mapper: JsonMapper = JsonMapper.builder().build(),
) : ReprocessingQueuePort {
    override fun enqueue(
        lane: Lane,
        partitionKey: String,
        work: ReprocessingWorkItem,
    ) {
        val doc = ItemDocuments.of(work.item.docUuid)
        val payload =
            linkedMapOf<String, Any?>(
                "packType" to work.packType,
                "version" to work.version,
                "docUuid" to work.item.docUuid,
                "supersededVersion" to work.item.supersededVersion,
                "acknowledged" to work.item.acknowledged,
                "storedCode" to work.item.storedVerdict.code,
                "storedSeverity" to work.item.storedVerdict.severity,
                "pageNumber" to doc.pageNumber,
                "position" to doc.position,
                "periodRef" to doc.periodRef,
                "entityRef" to doc.entityRef,
                "transactionDate" to doc.transactionDate.toString(),
                "internalTaxCode" to doc.internalTaxCode,
                "documentFunction" to doc.documentFunction,
                "precedingDocumentDate" to doc.precedingDocumentDate?.toString(),
                "supplyType" to doc.supplyType,
                "sgdValuesAbsent" to doc.sgdValuesAbsent,
                "taxableValue" to doc.taxableValue.toPlainString(),
                "gstValue" to doc.gstValue.toPlainString(),
            )
        queue.enqueue(QueueEntry(partitionKey, lane, mapper.writeValueAsString(payload), CONSUMER))
    }

    companion object {
        const val CONSUMER = "rules-reprocessing"
    }
}

/**
 * The inbox handler of `PackReprocessingPopulationRecorded`, in the tenant of the envelope. A page of another run,
 * another tenant, an entity the run did not request or a repeated page number enqueues nothing
 * ([HandleOutcome.IGNORED]). The stored verdict of each document is its evaluation under the bad pack version.
 */
class ReprocessingPageRecordedHandler(
    private val runs: JooqReprocessingStore,
    private val useCase: ReprocessPopulation,
    private val evaluation: PackEvaluationPort,
    private val mapper: JsonMapper = JsonMapper.builder().build(),
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val id = text(data, "reprocessingId", MAX_TEXT)
        val entity = text(data, "entityRef", MAX_TEXT)
        val number = data.get("pageNumber")?.takeIf { it.isInt && it.asInt() >= 1 }?.asInt() ?: bad("pageNumber")
        val last = data.get("lastPage")?.takeIf { it.isBoolean }?.asBoolean() ?: bad("lastPage is not a boolean")
        val documents = data.get("documents")?.takeIf { it.isArray } ?: bad("documents is not an array")
        if (documents.size() > MAX_ITEMS) bad("documents holds more than $MAX_ITEMS items")
        val parsed = (0 until documents.size()).map { document(documents.get(it), entity, number, it + 1) }
        val key = runs.admitPage(id, entity, number) ?: return HandleOutcome.IGNORED
        val badVersion = key.version.removeSuffix(PackCorrection.SUFFIX)
        val facts = parsed.associate { it.doc.docUuid to it.doc }
        val items =
            parsed.map {
                val stored = evaluation.evaluate(event.tenant, key.packType, badVersion, it.doc)
                PopulationItem(it.doc.docUuid, it.supersededVersion.toString(), it.acknowledged, stored)
            }
        ItemDocuments.with(facts) { useCase.pageArrived(key.packType, key.version, PopulationPage(entity, last, items)) }
        return HandleOutcome.APPLIED
    }

    private class ParsedItem(
        val supersededVersion: Int,
        val acknowledged: Boolean,
        val doc: SampledDocument,
    )

    private fun document(
        node: JsonNode,
        pageEntity: String,
        pageNumber: Int,
        position: Int,
    ): ParsedItem {
        if (!node.isObject) bad("a page item is not an object")
        val docUuid = text(node, "docUuid", MAX_UUID)
        try {
            UUID.fromString(docUuid)
        } catch (e: IllegalArgumentException) {
            throw ContractViolation("docUuid is not a UUID", e)
        }
        val superseded = node.get("supersededVersion")?.takeIf { it.isInt && it.asInt() >= 1 }?.asInt() ?: bad("supersededVersion")
        val entity = text(node, "entityRef", MAX_TEXT)
        if (entity != pageEntity) bad("a page item names another entity than its page")
        val doc =
            SampledDocument(
                pageNumber = pageNumber,
                position = position,
                docUuid = docUuid,
                periodRef = node.get("periodRef")?.takeIf { it.isString }?.asString().orEmpty(),
                entityRef = entity,
                transactionDate = date(node, "transactionDate") ?: bad("transactionDate is missing"),
                internalTaxCode = text(node, "internalTaxCode", MAX_TEXT),
                documentFunction = text(node, "documentFunction", MAX_TEXT),
                precedingDocumentDate = date(node, "precedingDocumentDate"),
                supplyType = node.get("supplyType")?.takeIf { it.isString }?.asString(),
                sgdValuesAbsent = node.get("sgdValuesAbsent")?.takeIf { it.isBoolean }?.asBoolean() ?: false,
                taxableValue = money(node, "taxableValue"),
                gstValue = money(node, "gstValue"),
            )
        val acknowledged = node.get("acknowledged")?.takeIf { it.isBoolean }?.asBoolean() ?: bad("acknowledged")
        return ParsedItem(superseded, acknowledged, doc)
    }

    private fun date(
        node: JsonNode,
        name: String,
    ): LocalDate? =
        node.get(name)?.takeIf { it.isString }?.let {
            try {
                LocalDate.parse(it.asString())
            } catch (e: java.time.format.DateTimeParseException) {
                throw ContractViolation("$name is not a date", e)
            }
        }

    private fun money(
        node: JsonNode,
        name: String,
    ): BigDecimal =
        node.get(name)?.takeIf { it.isString && MONEY.matches(it.asString()) }?.let { BigDecimal(it.asString()) }
            ?: bad("$name is not a decimal string")

    private fun text(
        node: JsonNode,
        name: String,
        max: Int,
    ): String =
        node.get(name)?.takeIf { it.isString }?.asString()?.takeIf { it.isNotEmpty() && it.length <= max }
            ?: bad("$name is missing, empty or too long")

    private fun parse(text: String): JsonNode =
        try {
            mapper.readTree(text)
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private fun bad(reason: String): Nothing = throw ContractViolation(reason)

    private companion object {
        const val MAX_TEXT = 40
        const val MAX_UUID = 36
        const val MAX_ITEMS = 500
        val MONEY = Regex("^-?\\d{1,17}(\\.\\d{1,2})?$")
    }
}

/**
 * The one worker loop: [drain] claims the next item (the queue applies the lane order and the lane rate limits),
 * processes it in its own tenant's transaction through [ReprocessPopulation.process] and completes it; an item whose
 * processing throws is released for another attempt (at-least-once; the resubmission add is idempotent).
 */
class ReprocessingWorker(
    private val queue: WorkQueue,
    private val scope: TenantScope,
    private val useCase: ReprocessPopulation,
    private val mapper: JsonMapper = JsonMapper.builder().build(),
) {
    private val log = LoggerFactory.getLogger(ReprocessingWorker::class.java)

    /** Claims and processes items until the queue yields none; returns how many were processed. */
    fun drain(): Int {
        var done = 0
        while (true) {
            val item = queue.claimNext() ?: return done
            if (item.lane != Lane.REPROCESSING) {
                queue.release(item) // another service's lane is never processed here
                return done
            }
            process(item)
            done++
        }
    }

    private fun process(item: ClaimedItem) {
        try {
            scope.bind(item.tenant, WORKLOAD) {
                scope.transactions.inTransaction {
                    val payload = mapper.readTree(queue.payloadOf(item))
                    val doc = documentOf(payload)
                    val populationItem =
                        PopulationItem(
                            doc.docUuid,
                            payload.get("supersededVersion").asString(),
                            payload.get("acknowledged").asBoolean(),
                            DocumentOutcome(
                                payload.get("storedCode").asString(),
                                payload.get("storedSeverity")?.takeUnless { it.isNull }?.asString(),
                            ),
                        )
                    ItemDocuments.with(mapOf(doc.docUuid to doc)) {
                        useCase.process(payload.get("packType").asString(), payload.get("version").asString(), populationItem)
                    }
                    queue.complete(item)
                }
            }
        } catch (e: RuntimeException) {
            log.warn("reprocessing item {} released after a failure", item.seq, e)
            scope.bind(item.tenant, WORKLOAD) { scope.transactions.inTransaction { queue.release(item) } }
        }
    }

    private fun documentOf(p: JsonNode) =
        SampledDocument(
            pageNumber = p.get("pageNumber").asInt(),
            position = p.get("position").asInt(),
            docUuid = p.get("docUuid").asString(),
            periodRef = p.get("periodRef").asString(),
            entityRef = p.get("entityRef").asString(),
            transactionDate = LocalDate.parse(p.get("transactionDate").asString()),
            internalTaxCode = p.get("internalTaxCode").asString(),
            documentFunction = p.get("documentFunction").asString(),
            precedingDocumentDate = p.get("precedingDocumentDate")?.takeUnless { it.isNull }?.let { LocalDate.parse(it.asString()) },
            supplyType = p.get("supplyType")?.takeUnless { it.isNull }?.asString(),
            sgdValuesAbsent = p.get("sgdValuesAbsent").asBoolean(),
            taxableValue = BigDecimal(p.get("taxableValue").asString()),
            gstValue = BigDecimal(p.get("gstValue").asString()),
        )

    private companion object {
        /** The workload principal the worker binds per claimed item (ARC-21). */
        const val WORKLOAD = "svc:rules-svc/job:reprocessing"
    }
}
