package com.dynokore.gst.payload.domain.header

import java.time.LocalDate

/**
 * The document header terms the builders write (INV-REQ-714): IBT-001 from the number issued to the customer and
 * IBT-002 from the document date. The internal accounting reference has no component here and is never transmitted.
 * A number that is null or blank is absent; a present number is written exactly as given.
 * The Invoice Note (IBT-022) is optional here: a non-null note is written as one cbc:Note exactly as given, a null note
 * writes none, and the IRAS MDE completeness check catches a missing one (INV-REQ-695).
 */
data class DocumentHeaderInput(
    val documentNumber: String?,
    val documentDate: LocalDate?,
    val invoiceNote: String? = null,
)
