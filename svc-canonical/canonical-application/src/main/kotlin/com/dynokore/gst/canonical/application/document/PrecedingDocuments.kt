package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.PrecedingCandidate
import com.dynokore.gst.canonical.domain.document.PrecedingDocumentSelector
import com.dynokore.gst.canonical.domain.document.PrecedingResolution
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.LocalDate
import java.util.UUID

/**
 * Port: the documents of the session's tenant that a credit note's preceding number can name. The document type and
 * the fiscal year are not filtered; the credit note's own [PrecedingReference.creditNoteDocUuid] is excluded.
 */
interface PrecedingDocumentLookup {
    fun candidates(reference: PrecedingReference): List<PrecedingCandidate>
}

/** The credit note being recorded, as far as the link needs it. */
data class PrecedingReference(
    val creditNoteDocUuid: UUID,
    val entityId: UUID,
    val direction: Direction,
    val issuerPartyKey: String,
    val precedingDocumentNumber: String,
    val precedingDocumentDate: LocalDate?,
) {
    init {
        require(precedingDocumentNumber.length in 1..MAX_PRECEDING_NUMBER) {
            "precedingDocumentNumber length is outside 1..$MAX_PRECEDING_NUMBER"
        }
    }

    private companion object {
        const val MAX_PRECEDING_NUMBER = 64 // the contract limit of precedingDocumentNumber
    }
}

/**
 * Resolves the preceding document of a credit note to its Doc UUID (INV-REQ-709, INV-Q-6311 option A). The caller
 * invokes it only for a credit note whose preceding number is populated; the reference cannot be built without one.
 */
class ResolvePrecedingDocUuid(
    private val lookup: PrecedingDocumentLookup,
    private val transaction: TransactionRunner,
) {
    fun resolvePrecedingDocUuid(creditNote: PrecedingReference): PrecedingResolution =
        PrecedingDocumentSelector.select(
            transaction.inTransaction { lookup.candidates(creditNote) },
            creditNote.precedingDocumentDate,
        )
}
