package com.dynokore.gst.consoleBff.adapters.exceptions

import com.dynokore.gst.consoleBff.application.exceptions.DocumentReleasedReader
import com.dynokore.gst.consoleBff.domain.exceptions.DocumentReleasedFacts

/** [DocumentReleasedReader] over Jackson; a missing or mistyped required member is a `ContractViolation`. */
class JacksonDocumentReleasedReader : DocumentReleasedReader {
    override fun read(dataJson: String): DocumentReleasedFacts {
        val type = "DocumentReleased"
        val data = ExceptionPayload.parse(dataJson, type)
        return DocumentReleasedFacts(
            docUuid = ExceptionPayload.required(data, "docUuid", type),
            docsequence = ExceptionPayload.sequence(data, type),
            releasedHoldReasons = ExceptionPayload.reasons(data, "releasedHoldReasons", type),
        )
    }
}
