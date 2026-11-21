package com.dynokore.gst.canonical.adapters.events

import com.dynokore.gst.canonical.application.sample.SampleFunctionUnmappedException
import com.dynokore.gst.canonical.domain.dating.PeriodAssignment
import com.dynokore.gst.canonical.domain.dating.PeriodDecision
import com.dynokore.gst.canonical.domain.dating.PeriodSpan
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/** The pack type of a reprocessing request and the column of the stored version that belongs to it. */
enum class ReprocessingPackType { SCOPE, MAPPING, MDE }

/** A stored document of the population: the current version of a document evaluated under the bad pack. */
data class PopulationDocument(
    val docUuid: UUID,
    /** The current version number of the document. */
    val version: Int,
    val transactionDate: LocalDate,
    val documentTypeCode: Int,
    val internalTaxCode: String,
    val precedingDocumentDate: String?,
    val taxableValue: String,
    val gstValue: String,
    val sgdValuesAbsent: Boolean,
    val supplyType: String?,
)

/** Port: the population read of a reprocessing run, bound to one tenant. */
fun interface ReprocessingPopulation {
    /**
     * At most [limit] documents of [entityId] whose current version stores [badPackVersion] in the column of
     * [packType] and whose transaction date is on or after [effectiveFrom], by doc_uuid after [afterDocUuid].
     */
    @Suppress("LongParameterList") // the signature of the population read of INV-REQ-1003
    fun read(
        tenantId: UUID,
        entityId: UUID,
        packType: ReprocessingPackType,
        badPackVersion: String,
        effectiveFrom: LocalDate,
        limit: Int,
        afterDocUuid: UUID?,
    ): List<PopulationDocument>
}

/** Port: the (doc, version) keys among [keys] that have an acknowledgement row of the tenant in the replica. */
fun interface AcknowledgedVersions {
    fun acknowledged(
        tenantId: UUID,
        keys: List<Pair<UUID, Int>>,
    ): Set<Pair<UUID, Int>>
}

/** Port: the period spans of an entity, in start order. */
fun interface ReprocessingSpans {
    fun spansOf(
        tenantId: UUID,
        entityRef: String,
    ): List<PeriodSpan>
}

/** Port: the function name the scope pack gives a stored document type code, or null. */
fun interface ReprocessingFunctions {
    fun functionOf(documentTypeCode: Int): String?
}

/** The marker of an answered request: true when the (tenant, reprocessing id) marker was new and inserted. */
fun interface ReprocessingRequestMarker {
    fun markAnswered(
        tenantId: UUID,
        reprocessingId: String,
    ): Boolean
}

/** The ports of the reprocessing answer that read stored data. */
class ReprocessingReads(
    val identities: SampleEntityIdentities,
    val population: ReprocessingPopulation,
    val acknowledgements: AcknowledgedVersions,
    val spans: ReprocessingSpans,
    val functions: ReprocessingFunctions,
)

/**
 * Consumes `PackReprocessingRequested` (platform event, INV-REQ-1003) with the tenant binding of the request's
 * envelope and answers it with `PackReprocessingPopulationRecorded` pages through the outbox. The handler runs in the
 * consumer's tenant transaction, so the marker, the pages and the inbox row commit together; any exception rolls all
 * of them back and the event is retried. The data is checked against
 * `service-runtime/schemas/pack-reprocessing-requested-payload.schema.json`; what does not validate, and a `packType`
 * outside SCOPE, MAPPING and MDE (it has no stored version to compare with), is a [ContractViolation] (dead letter at
 * once) before any marker or read. The marker is inserted before anything is read (`ON CONFLICT DO NOTHING`): an
 * insert that adds no row means the run was answered for the tenant, and nothing is published. The handler writes no
 * canonical document, version or state.
 */
class ReprocessingRequestInbox(
    private val reads: ReprocessingReads,
    private val markers: ReprocessingRequestMarker,
    private val publisher: ReprocessingPagePublisher,
    private val maxItems: () -> Int,
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        if (event.tenant.isPlatform) throw ContractViolation("the envelope carries no tenantid")
        val tenant = event.tenant.value
        val data = parse(event.dataJson)
        val reprocessingId = text(data, "reprocessingId", MAX_ID)
        val entityRef = text(data, "entityRef", MAX_ID)
        val packType = packType(text(data, "packType", MAX_ID))
        val bad = text(data, "badPackVersion", MAX_VERSION)
        text(data, "supersedingPackVersion", MAX_VERSION)
        val from = date(data, "effectiveFrom")
        val entityId =
            reads.identities.entityIdOf(tenant, entityRef)
                ?: error("entity $entityRef has no identity row for the tenant of the request")
        if (!markers.markAnswered(tenant, reprocessingId)) return HandleOutcome.IGNORED
        answer(Request(tenant, reprocessingId, entityRef, entityId, packType, bad, from))
        return HandleOutcome.APPLIED
    }

    @Suppress("LongParameterList") // the members of the request, as the event names them
    private class Request(
        val tenant: UUID,
        val reprocessingId: String,
        val entityRef: String,
        val entityId: UUID,
        val packType: ReprocessingPackType,
        val bad: String,
        val from: LocalDate,
    )

    private fun answer(request: Request) {
        val max = maxItems()
        require(max >= 1) { "the page limit must be at least 1" }
        val spans = reads.spans.spansOf(request.tenant, request.entityRef)
        var after: UUID? = null
        var pageNumber = 1
        do {
            val read =
                reads.population.read(
                    request.tenant,
                    request.entityId,
                    request.packType,
                    request.bad,
                    request.from,
                    max + 1,
                    after,
                )
            val last = read.size <= max
            val documents = read.take(max)
            val acknowledged =
                reads.acknowledgements.acknowledged(request.tenant, documents.map { it.docUuid to it.version })
            val items = documents.map { itemOf(request, it, it.docUuid to it.version in acknowledged, spans) }
            publisher.publish(
                request.tenant,
                ReprocessingPage(request.reprocessingId, request.entityRef, pageNumber, last, items),
            )
            after = documents.lastOrNull()?.docUuid
            pageNumber++
        } while (!last)
    }

    private fun itemOf(
        request: Request,
        document: PopulationDocument,
        acknowledged: Boolean,
        spans: List<PeriodSpan>,
    ): Map<String, Any> {
        val function =
            reads.functions.functionOf(document.documentTypeCode)
                ?: throw SampleFunctionUnmappedException(document.documentTypeCode)
        val period = PeriodAssignment.assign(document.transactionDate, spans) as? PeriodDecision.Assigned
        return buildMap {
            put("docUuid", document.docUuid.toString())
            put("supersededVersion", document.version)
            put("acknowledged", acknowledged)
            put("entityRef", request.entityRef)
            put("transactionDate", document.transactionDate.toString())
            put("internalTaxCode", document.internalTaxCode)
            put("documentFunction", function)
            document.precedingDocumentDate?.let { put("precedingDocumentDate", it) }
            put("taxableValue", document.taxableValue)
            put("gstValue", document.gstValue)
            if (document.sgdValuesAbsent) put("sgdValuesAbsent", true)
            period?.let { put("periodRef", it.periodRef) }
            document.supplyType?.takeIf { it.isNotEmpty() }?.let { put("supplyType", it) }
        }
    }

    private fun packType(value: String): ReprocessingPackType =
        ReprocessingPackType.entries.firstOrNull { it.name == value }
            ?: throw ContractViolation("packType is not one of SCOPE, MAPPING and MDE")

    private fun text(
        data: JsonNode,
        member: String,
        max: Int,
    ): String {
        val value = data.get(member)
        if (value == null || !value.isString || value.asString().length !in 1..max) {
            throw ContractViolation("$member is missing or not a text of 1 to $max characters")
        }
        return value.asString()
    }

    private fun date(
        data: JsonNode,
        member: String,
    ): LocalDate {
        val raw = text(data, member, MAX_DATE)
        return try {
            LocalDate.parse(raw)
        } catch (e: DateTimeParseException) {
            throw ContractViolation("$member is not a date", e)
        }
    }

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text).also { if (!it.isObject) throw ContractViolation("the payload is not a JSON object") }
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private companion object {
        const val MAX_ID = 40
        const val MAX_VERSION = 16
        const val MAX_DATE = 10
    }
}
