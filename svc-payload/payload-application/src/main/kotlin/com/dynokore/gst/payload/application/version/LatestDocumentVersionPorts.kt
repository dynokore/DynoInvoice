package com.dynokore.gst.payload.application.version

import java.util.UUID

/** The highest stored version of a document and the entity it belongs to. */
data class LatestVersionFacts(
    val version: Int,
    val entityRef: String,
)

/** The stored document versions; the tenant is the one bound to the request. */
fun interface LatestDocumentVersions {
    /** The facts of the highest-numbered stored version, or nothing when the document has no stored version. */
    fun latest(docUuid: UUID): LatestVersionFacts?
}
