package com.dynokore.gst.reconciliation.domain.variance

/** What the read model holds of a document version that decides its variance class; both are free strings there. */
data class DocumentFacts(
    val scopeVerdict: String?,
    val notSubmittableReason: String?,
)
