package com.dynokore.gst.canonical.application.sample

import com.dynokore.gst.canonical.domain.dating.PeriodAssignment
import com.dynokore.gst.canonical.domain.dating.PeriodDecision
import com.dynokore.gst.canonical.domain.dating.PeriodSpan
import java.time.LocalDate
import java.util.UUID

/**
 * INV-REQ-772 behavior 1: answers a request for the dry-run sample with pages of the entity's documents in the window
 * of the request. The window dates and the period of every item come from the spans of one [SamplePeriodCalendar];
 * the function of every item from [SampleDocumentFunctions]. [maxItems] is the page limit of the contract. The caller
 * runs [answer] inside one transaction bound to the tenant of the request.
 */
class AnswerSampleRequest(
    private val reader: SampleDocumentReader,
    private val publisher: SamplePagePublisher,
    private val functions: SampleDocumentFunctions,
    private val calendar: SamplePeriodCalendar,
    private val maxItems: Int,
) {
    init {
        require(maxItems >= 1) { "maxItems must be at least 1" }
    }

    fun answer(request: SampleRequest) {
        val spans = spansOf(request)
        val from = spans.first { it.periodRef == request.firstPeriodRef }.start
        val to = spans.first { it.periodRef == request.lastPeriodRef }.end
        val functionOf = functionsOf(request, from, to)
        var after: UUID? = null
        var pageNumber = 1
        do {
            val read = reader.read(request.tenantId, request.entityId, from, to, maxItems + 1, after)
            val last = read.size <= maxItems
            val documents = read.take(maxItems)
            val items = documents.map { itemOf(request, it, spans, functionOf) }
            publisher.publish(
                request.tenantId,
                request.entityRef,
                SamplePage(request.dryRunId, pageNumber, last, items),
            )
            after = documents.lastOrNull()?.docUuid
            pageNumber++
        } while (!last)
    }

    private fun spansOf(request: SampleRequest): List<PeriodSpan> {
        val answer = calendar.spans(request.tenantId, request.entityRef, request.firstPeriodRef, request.lastPeriodRef)
        val spans = (answer as? SamplePeriods.Known)?.spans ?: unresolved("the period calendar has no answer")
        val first = spans.firstOrNull { it.periodRef == request.firstPeriodRef }
        val last = spans.firstOrNull { it.periodRef == request.lastPeriodRef }
        if (first == null) unresolved("the first period reference is not in the spans")
        if (last == null) unresolved("the last period reference is not in the spans")
        if (first.start.isAfter(last.start)) unresolved("the first period starts after the last")
        spans.zipWithNext().forEach { (a, b) ->
            if (b.start != a.end.plusDays(1)) unresolved("the spans are not ordered and contiguous")
        }
        return spans
    }

    private fun functionsOf(
        request: SampleRequest,
        from: LocalDate,
        to: LocalDate,
    ): Map<Int, String> =
        reader
            .distinctTypeCodes(request.tenantId, request.entityId, from, to)
            .associateWith { code -> (functions.functionOf(code) ?: throw SampleFunctionUnmappedException(code)).name }

    private fun itemOf(
        request: SampleRequest,
        document: SampleDocument,
        spans: List<PeriodSpan>,
        functionOf: Map<Int, String>,
    ): SampleItem {
        val period =
            PeriodAssignment.assign(document.transactionDate, spans) as? PeriodDecision.Assigned
                ?: unresolved("document ${document.docUuid} lies in no single period of the window")
        return SampleItem(
            entityRef = request.entityRef,
            transactionDate = document.transactionDate,
            internalTaxCode = document.internalTaxCode,
            documentFunction = checkNotNull(functionOf[document.documentTypeCode]),
            precedingDocumentDate = document.precedingDocumentDate,
            taxableValue = document.taxableValue,
            gstValue = document.gstValue,
            sgdValuesAbsent = if (document.sgdValuesAbsent) true else null,
            periodRef = period.periodRef,
            docUuid = document.docUuid,
            supplyType = document.supplyType,
        )
    }

    private fun unresolved(reason: String): Nothing = throw SampleWindowUnresolvedException(reason)
}
