package com.dynokore.gst.canonical.application.sample

import com.dynokore.gst.canonical.domain.dating.PeriodSpan
import com.dynokore.gst.canonical.domain.derivation.UpstreamFunction
import java.time.LocalDate
import java.util.UUID

/** One request for the dry-run sample (PackDryRunSampleRequested): the tenant, the entity and a window of periods. */
data class SampleRequest(
    val tenantId: UUID,
    val dryRunId: String,
    /** The entity as the request names it; it is the `entityRef` of every item. */
    val entityRef: String,
    /** The id of the entity in `canonical.document.entity_id`. */
    val entityId: UUID,
    val firstPeriodRef: String,
    val lastPeriodRef: String,
)

/** The answer of the period calendar: the spans of the requested range, or no answer. */
sealed interface SamplePeriods {
    data class Known(
        val spans: List<PeriodSpan>,
    ) : SamplePeriods

    data object Unknown : SamplePeriods
}

/** Port: the one source of period boundaries, for the window dates and for the period of every item. */
fun interface SamplePeriodCalendar {
    fun spans(
        tenantId: UUID,
        entityRef: String,
        firstPeriodRef: String,
        lastPeriodRef: String,
    ): SamplePeriods
}

/** Port: the scope pack's classification of a stored document type code; null: no row, or rows that disagree. */
fun interface SampleDocumentFunctions {
    fun functionOf(documentTypeCode: Int): UpstreamFunction?
}

/** A stored document as the sample reads it; the values are already the signed SGD values of the item. */
data class SampleDocument(
    val docUuid: UUID,
    val transactionDate: LocalDate,
    val documentTypeCode: Int,
    val internalTaxCode: String,
    /** The `value` of `precedingDocumentDate` of the current version, when it holds one. */
    val precedingDocumentDate: String?,
    val taxableValue: String,
    val gstValue: String,
    /** True only for a foreign-currency document without stated SGD totals (values are then 0.00). */
    val sgdValuesAbsent: Boolean,
)

/** Port: the read side of the sample over canonical-svc's own stored versions, bound to one tenant. */
interface SampleDocumentReader {
    /** The distinct `document_type_code` of the entity's documents whose transaction date is in [from]..[to]. */
    fun distinctTypeCodes(
        tenantId: UUID,
        entityId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): Set<Int>

    /**
     * At most [limit] documents of the entity in [from]..[to], by doc_uuid, after [afterDocUuid]. Throws
     * [SampleDocumentWithoutSubtotalException] for a document of the read that has no subtotal row.
     */
    @Suppress("LongParameterList") // the signature of the window read of INV-REQ-772 b1
    fun read(
        tenantId: UUID,
        entityId: UUID,
        from: LocalDate,
        to: LocalDate,
        limit: Int,
        afterDocUuid: UUID?,
    ): List<SampleDocument>
}

/** One item of `documents` of PackDryRunSampleRecorded. A null member is not written. */
data class SampleItem(
    val entityRef: String,
    val transactionDate: LocalDate,
    val internalTaxCode: String,
    val documentFunction: String,
    val precedingDocumentDate: String?,
    val taxableValue: String,
    val gstValue: String,
    /** True or null: the member is written only when true. */
    val sgdValuesAbsent: Boolean?,
    val periodRef: String,
    val docUuid: UUID,
) {
    fun payload(): Map<String, Any> =
        buildMap {
            put("entityRef", entityRef)
            put("transactionDate", transactionDate.toString())
            put("internalTaxCode", internalTaxCode)
            put("documentFunction", documentFunction)
            precedingDocumentDate?.let { put("precedingDocumentDate", it) }
            put("taxableValue", taxableValue)
            put("gstValue", gstValue)
            sgdValuesAbsent?.let { put("sgdValuesAbsent", it) }
            put("periodRef", periodRef)
            put("docUuid", docUuid.toString())
        }
}

/** One page of the sample; [payload] is the payload of PackDryRunSampleRecorded. */
data class SamplePage(
    val dryRunId: String,
    val pageNumber: Int,
    val lastPage: Boolean,
    val documents: List<SampleItem>,
) {
    fun payload(): Map<String, Any> =
        mapOf(
            "dryRunId" to dryRunId,
            "pageNumber" to pageNumber,
            "lastPage" to lastPage,
            "documents" to documents.map { it.payload() },
        )
}

/** Port: hands one page over (in slice 2 through the outbox). */
fun interface SamplePagePublisher {
    fun publish(
        tenantId: UUID,
        page: SamplePage,
    )
}

/** A document of the window has no subtotal row: a stored-data defect; the request fails, no code is made up. */
class SampleDocumentWithoutSubtotalException(
    val docUuid: UUID,
) : RuntimeException("document $docUuid has no subtotal row in its current version")

/** A stored document type code has no function in the scope pack; no page is handed over. */
class SampleFunctionUnmappedException(
    val documentTypeCode: Int,
) : RuntimeException("document type code $documentTypeCode has no document function in the scope pack")

/** The window of the request cannot be named by the period calendar. */
class SampleWindowUnresolvedException(
    reason: String,
) : RuntimeException(reason)
