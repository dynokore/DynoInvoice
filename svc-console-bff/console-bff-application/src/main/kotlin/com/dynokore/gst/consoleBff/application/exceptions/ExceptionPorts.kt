package com.dynokore.gst.consoleBff.application.exceptions

import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionRow

/** Read side of the exception store. */
interface ExceptionReads {
    fun openRowsOfDocument(
        tenantId: String,
        docUuid: String,
    ): List<ExceptionRow>

    /** Every stored row of the document, whatever its state. */
    fun rowsOfDocument(
        tenantId: String,
        docUuid: String,
    ): List<ExceptionRow>
}

/** Storage port of the exception read model. */
interface ExceptionStore : ExceptionReads {
    /** Keyed by tenantId and exceptionId; the stored row becomes the given row. */
    fun upsert(rows: List<ExceptionRow>)
}
