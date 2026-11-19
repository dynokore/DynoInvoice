package com.dynokore.gst.canonical.application.netting

import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedFacts
import com.dynokore.gst.canonical.application.document.CanonicalVersionWriter
import com.dynokore.gst.canonical.application.document.DocumentRowConflict
import com.dynokore.gst.canonical.application.document.NetDerivationFact
import com.dynokore.gst.canonical.domain.netting.Derived
import com.dynokore.gst.canonical.domain.netting.NetDerivation
import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import java.util.UUID

/**
 * INV-REQ-558 behavior 6: a new ERP version of an original whose predecessor is a system-derived net version is
 * re-derived net of the same credit notes, unless the ERP declares them itself. It opens no transaction: the caller
 * runs [afterErpVersion] in the transaction that wrote the ERP version, so the derived version is written through
 * [CanonicalVersionWriter] in the same transaction. A refused derivation writes nothing: the ERP version stays as
 * posted and submission-svc decides the credit note again. Declared keys are compared exactly, never by prefix or case.
 */
class NetRederivation(
    private val records: NetDerivationRecords,
    private val documents: NetDerivationDocuments,
    private val versions: NetDerivedVersions,
    private val writer: CanonicalVersionWriter,
    private val factsFor: (originalDocUuid: UUID, netDerivation: NetDerivationFact) -> CanonicalVersionCreatedFacts,
) {
    fun afterErpVersion(
        docUuid: UUID,
        newVersion: Int,
        declaredCreditNoteKeys: List<String>,
    ) {
        val predecessor = records.recordOf(docUuid, newVersion - 1) ?: return
        val declared = declaredCreditNoteKeys.toSet()
        if (predecessor.creditNotes.any { it.docUuid.toString() in declared }) return
        val base = documents.base(docUuid, newVersion) ?: return
        val notes = predecessor.creditNotes.map { documents.creditNote(it.docUuid, it.version) }
        if (notes.any { it == null }) return
        val derived = NetDerivation.derive(docUuid, base, notes.filterNotNull())
        if (derived is Derived) write(docUuid, newVersion, predecessor, derived)
    }

    private fun write(
        docUuid: UUID,
        newVersion: Int,
        predecessor: NetDerivationFact,
        derived: Derived,
    ) {
        val fact = NetDerivationFact(baseVersion = newVersion, creditNotes = predecessor.creditNotes)
        val outcome =
            writer.next(docUuid, newVersion, versions.build(docUuid, newVersion, derived), factsFor(docUuid, fact))
        if (outcome !is UpdateOutcome.Updated) throw DocumentRowConflict(docUuid, newVersion)
        records.record(docUuid, outcome.version.toInt(), newVersion, predecessor.creditNotes)
    }
}
