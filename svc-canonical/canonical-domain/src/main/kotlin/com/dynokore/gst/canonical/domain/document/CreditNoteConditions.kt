package com.dynokore.gst.canonical.domain.document

import com.dynokore.gst.canonical.domain.netting.DocumentFunction

/** One refusal: the catalogue code and the HTTP status. */
data class ConditionViolation(
    val code: String,
    val httpStatus: Int,
)

/**
 * The credit-note conditions of a record request (INV-REQ-371, INV-Q-6507 A; INV-AC-13667): a document sent as
 * ADJUSTMENT meets the same conditions as CREDIT_NOTE. Pure over presence flags; it decides nothing about tax.
 */
object CreditNoteConditions {
    private val ING_019 = ConditionViolation("ING-019", 400)
    private val SRT_001 = ConditionViolation("SRT-001", 400)

    @Suppress("LongParameterList")
    fun violations(
        documentFunction: DocumentFunction,
        precedingDocumentNumberPresent: Boolean,
        precedingDocumentDatePresent: Boolean,
        precedingBlankJustificationPresent: Boolean,
        evidenceRefPresent: Boolean,
        nettedCreditNoteKeysPresent: Boolean,
        gstNotAdjustedPresent: Boolean,
    ): List<ConditionViolation> {
        val creditNoteLike =
            documentFunction == DocumentFunction.CREDIT_NOTE || documentFunction == DocumentFunction.ADJUSTMENT
        val result = mutableListOf<ConditionViolation>()
        val linked = precedingDocumentNumberPresent || precedingDocumentDatePresent
        val justified = precedingBlankJustificationPresent && evidenceRefPresent
        if (creditNoteLike && !linked && !justified) result += ING_019
        if (nettedCreditNoteKeysPresent && documentFunction != DocumentFunction.INVOICE) result += SRT_001
        if (gstNotAdjustedPresent && !creditNoteLike) result += SRT_001
        return result
    }
}
