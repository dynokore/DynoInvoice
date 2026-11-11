package com.dynokore.gst.submission.application.register

import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.register.ListFactsPolicy
import com.dynokore.gst.submission.domain.register.ListFactsResult

/**
 * [DocumentKindResolver] from the document type code of the list facts: 380 is an invoice, 381 a credit note
 * (INV-REQ-975 "380/381"); every other code, and incomplete list facts, give null (the handler dead-letters it).
 */
class DocumentTypeKindResolver : DocumentKindResolver {
    override fun kindOf(data: CanonicalVersionCreatedData): RowKind? =
        when (val result = ListFactsPolicy.extract(data.payload)) {
            is ListFactsResult.Complete -> {
                when (result.facts.documentTypeCode) {
                    "380" -> RowKind.INVOICE
                    "381" -> RowKind.CREDIT_NOTE
                    else -> null
                }
            }

            is ListFactsResult.Incomplete -> {
                null
            }
        }
}
