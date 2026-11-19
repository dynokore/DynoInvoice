package com.dynokore.gst.canonical.application.netting

import com.dynokore.gst.canonical.application.document.RecordDocumentCommand
import com.dynokore.gst.canonical.domain.netting.NettingLinks
import com.dynokore.gst.canonical.domain.netting.RecordedDocument
import java.util.UUID

/** Finds the recorded documents that source keys name; a key naming nothing is absent from the answer. */
interface RecordedDocumentsByKey {
    fun lookup(
        tenantId: UUID,
        sourceSystem: String,
        keys: List<String>,
    ): Map<String, RecordedDocument>
}

/** The Doc UUIDs of the credit notes a record declares in `nettedCreditNoteKeys` (INV-REQ-558 behavior 4). */
interface DeclaredCreditNotes {
    fun of(
        command: RecordDocumentCommand,
        entityId: UUID,
    ): List<UUID>
}

/**
 * Resolves the declared source keys through [NettingLinks.forRevision]. The port is called only for a non-empty key
 * list; no exception is swallowed (a repeated key makes `forRevision` throw).
 */
class NettingLinksDeclaredCreditNotes(
    private val lookup: RecordedDocumentsByKey,
) : DeclaredCreditNotes {
    override fun of(
        command: RecordDocumentCommand,
        entityId: UUID,
    ): List<UUID> {
        val keys = command.nettedCreditNoteKeys
        if (keys.isEmpty()) return emptyList()
        val recorded = lookup.lookup(command.tenantId, command.sourceSystem, keys)
        return NettingLinks.forRevision(entityId, keys, recorded).resolved
    }
}
