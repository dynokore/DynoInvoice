package com.dynokore.gst.canonical.application.netting

import com.dynokore.gst.canonical.application.document.NetDerivationCreditNoteFact
import com.dynokore.gst.canonical.application.document.NetDerivationFact
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.netting.Derived
import com.dynokore.gst.canonical.domain.netting.NetBase
import com.dynokore.gst.canonical.domain.netting.NetCreditNote
import com.dynokore.gst.canonical.domain.netting.NetRefusal
import java.util.UUID

/** A credit note named by a net derivation request: its document and the version to net. */
typealias NetCreditNoteRef = NetDerivationCreditNoteFact

/** Reads the documents a net derivation needs. A null answer means the document or version is unknown. */
interface NetDerivationDocuments {
    /** The current version number of [docUuid], or null when the document is unknown. */
    fun currentVersion(docUuid: UUID): Int?

    /** The base of the named version of the original, or null when unknown. */
    fun base(
        docUuid: UUID,
        version: Int,
    ): NetBase?

    /** The named version of a credit note, or null when unknown. */
    fun creditNote(
        docUuid: UUID,
        version: Int,
    ): NetCreditNote?
}

/** Stages a `NetDerivationRefused` event in the caller's transaction (transactional outbox). */
interface NetDerivationRefusedPublisher {
    fun stage(
        originalDocUuid: UUID,
        requestEventId: UUID,
        reason: NetRefusal,
        creditNotes: List<NetCreditNoteRef>,
    )
}

/** Stores and reads the `netDerivation` of a version. */
interface NetDerivationRecords {
    fun record(
        docUuid: UUID,
        version: Int,
        baseVersion: Int,
        creditNotes: List<NetCreditNoteRef>,
    )

    /** The record of the version, or null when it has none (including a version that is not derived). */
    fun recordOf(
        docUuid: UUID,
        version: Int,
    ): NetDerivationFact?
}

/** The processed-request store of the idempotent consumer. */
interface ProcessedNetDerivationRequests {
    /** True once per [requestEventId]; false when it was marked before. */
    fun markIfNew(requestEventId: UUID): Boolean
}

/** Builds the derived version; the adapter copies the base document and replaces lines, subtotals and totals. */
interface NetDerivedVersions {
    fun build(
        docUuid: UUID,
        baseVersion: Int,
        derived: Derived,
    ): NewVersion
}
