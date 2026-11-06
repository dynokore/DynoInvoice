package com.dynokore.gst.payload.adapters.persistence

import com.dynokore.gst.payload.application.override.DocumentVersionFacts
import com.dynokore.gst.payload.application.override.DocumentVersions
import com.dynokore.gst.payload.application.version.VersionFactsReader
import java.util.UUID

/** The stored version facts as the override core reads them (INV-Q-17166 A); null when the reader returns null. */
class StoredDocumentVersions(
    private val reader: VersionFactsReader,
) : DocumentVersions {
    override fun find(
        docUuid: UUID,
        version: Int,
    ): DocumentVersionFacts? =
        reader.find(docUuid, version)?.let { facts ->
            facts.totalSgd?.let { DocumentVersionFacts(facts.entityRef, it) }
        }
}
