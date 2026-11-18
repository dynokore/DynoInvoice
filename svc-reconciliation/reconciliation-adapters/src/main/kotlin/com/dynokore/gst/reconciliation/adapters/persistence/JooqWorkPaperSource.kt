package com.dynokore.gst.reconciliation.adapters.persistence

import com.dynokore.gst.reconciliation.application.workpaper.GetWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.SourceWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.WorkPaperBox
import com.dynokore.gst.reconciliation.application.workpaper.WorkPaperSource
import com.dynokore.gst.reconciliation.application.workpaper.WorkPaperView
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper

/**
 * The source of the work-paper to seal (INV-T-24382, INV-REQ-656): the work-paper [GetWorkPaper] builds for the run,
 * through the run store of the caller's transaction and its tenant, as canonical JSON bytes. The entity reference is
 * the entity of the run, never the run id.
 *
 * The bytes are deterministic: the members have the names of the contract `WorkPaper`, the members of every object are
 * written in sorted order with no insignificant whitespace, money is the string of scale 2 `GetWorkPaper` already
 * holds, and an instant is its ISO-8601 UTC text. The `attestation` member is present only when the attestation read
 * side holds one. Against RFC 8785 (JSON Canonicalization Scheme) for these value types: member order is the UTF-16
 * code unit order (`String.compareTo`, as the RFC sorts); the only numbers are integers, which the RFC writes as
 * plain digits; strings use the short escapes `\b \f \n \r \t` and lower-case `\u00xx` for other control characters,
 * with every other character as UTF-8, as the RFC does. No floating-point value occurs.
 */
class JooqWorkPaperSource(
    private val workPaper: GetWorkPaper,
) : WorkPaperSource {
    override fun load(
        tenant: String,
        runId: String,
    ): SourceWorkPaper? {
        val view = workPaper.execute(tenant, runId) ?: return null
        return SourceWorkPaper(view.header.entityRef, MAPPER.writeValueAsBytes(document(view)))
    }

    private fun document(view: WorkPaperView): Map<String, Any?> {
        val doc = LinkedHashMap<String, Any?>()
        doc["unmappedLines"] =
            view.unmappedLines.map {
                linkedMapOf<String, Any?>(
                    "taxCode" to it.taxCode,
                    "lineNumber" to it.lineNumber,
                    "version" to it.version,
                    "docUuid" to it.docUuid,
                )
            }
        view.attestation?.let {
            doc["attestation"] =
                linkedMapOf<String, Any?>("attestedBy" to it.attestedBy, "attestedAt" to it.attestedAt.toString())
        }
        doc["supportingDocuments"] = view.supportingDocuments
        doc["unexplainedResidual"] = view.unexplainedResidual
        doc["boxes"] = view.boxes.map(::box)
        doc["header"] =
            linkedMapOf<String, Any?>(
                "returnForm" to view.header.returnForm,
                "returnVersion" to view.header.returnVersion,
                "preparedAt" to view.header.preparedAt.toString(),
                "periodRef" to view.header.periodRef,
                "entityRef" to view.header.entityRef,
            )
        doc["runId"] = view.runId
        return doc
    }

    private fun box(b: WorkPaperBox): Map<String, Any?> =
        linkedMapOf(
            "variance" to b.variance,
            "varianceClasses" to
                b.varianceClasses.map {
                    linkedMapOf<String, Any?>("varianceClass" to it.varianceClass, "amount" to it.amount)
                },
            "filedAmount" to b.filedAmount,
            "submittedAmount" to b.submittedAmount,
            "box" to b.box,
        )

    private companion object {
        val MAPPER: JsonMapper =
            JsonMapper
                .builder()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.INDENT_OUTPUT)
                .build()
    }
}
