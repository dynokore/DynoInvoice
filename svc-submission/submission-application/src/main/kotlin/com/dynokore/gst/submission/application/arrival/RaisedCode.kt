package com.dynokore.gst.submission.application.arrival

import com.dynokore.gst.submission.application.queue.CatalogueEntry

/**
 * One code raised for a document version (INV-AC-21086). The owner and disposition are read from the
 * catalogue and are null when the catalogue gives none; no value is guessed.
 */
data class RaisedCode(
    val docUuid: String,
    val version: Int,
    val entityRef: String,
    val periodRef: String,
    val code: String,
    val remediationOwner: String?,
    val disposition: String?,
) {
    companion object {
        fun of(
            docUuid: String,
            version: Int,
            entityRef: String,
            periodRef: String,
            code: String,
            entry: CatalogueEntry?,
        ): RaisedCode {
            require(version >= 1) { "version must be at least 1" }
            require(entityRef.isNotBlank()) { "entityRef must not be blank" }
            require(periodRef.isNotBlank()) { "periodRef must not be blank" }
            require(code.isNotBlank()) { "code must not be blank" }
            return RaisedCode(docUuid, version, entityRef, periodRef, code, entry?.owner, entry?.disposition)
        }
    }
}
