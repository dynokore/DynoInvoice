package com.dynokore.gst.rules.application

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.tenancykernel.TenantId

/** One credit note to code: its own date, its preceding-document fields as stated, and its lines. */
data class CodeCreditNoteCommand(
    val tenant: TenantId?,
    val ownDate: TransactionDate,
    val precedingDocumentNumber: String?,
    val precedingDocumentDate: TransactionDate?,
    val lines: List<DocumentLine>,
)

/** Where the date the rate and category code resolve at came from. */
enum class RateDateBasis {
    /** `precedingDocumentDate` was populated (INV-REQ-701 b1). */
    PRECEDING_DATE_STATED,

    /** Both preceding fields are blank: everything resolves by the credit note's own date (INV-REQ-702 b1). */
    OWN_DATE_BLANK_LINKED,
}

/**
 * The decision trace of the rate date: it names both dates, the date used and why (INV-REQ-685, INV-AC-746).
 * [justificationNote] is set for a blank-linked credit note and records that a rate-change straddle was considered
 * (INV-REQ-702 b2); the caller writes it under `precedingBlankJustification`.
 */
data class RateDateTrace(
    val ruleId: String,
    val ownDate: TransactionDate,
    val precedingDate: TransactionDate?,
    val rateDate: TransactionDate,
    val basis: RateDateBasis,
    val justificationNote: String?,
)

sealed interface CreditNoteCodingResult {
    /** Rate and category code coded at [trace].rateDate; scope, MDE, Schematron, period and due date use own date. */
    data class Coded(
        val document: CodedDocument,
        val trace: RateDateTrace,
    ) : CreditNoteCodingResult

    /** The coding of the document at the resolved rate date was refused (RUL-002, or lines refused). */
    data class NotCoded(
        val result: DocumentCodingResult,
        val trace: RateDateTrace,
    ) : CreditNoteCodingResult

    /**
     * The preceding number is populated, its date blank and the entity has no such document: RUL-008 (BLOCK, MANUAL,
     * Tax operations). No rate is taken from the credit note's own date (INV-AC-1819).
     */
    data class UnknownOriginal(
        val precedingDocumentNumber: String,
        val code: ErrorCode = ErrorCode.RUL_008,
    ) : CreditNoteCodingResult
}

/**
 * Credit-note rate and category-code resolution (INV-REQ-685, INV-REQ-701, INV-REQ-702): rate and code come from the
 * preceding document's date, everything else from the credit note's own date. It reads no clock: only dates.
 */
class CreditNoteCoding(
    private val coder: DocumentCoder,
) {
    fun code(command: CodeCreditNoteCommand): CreditNoteCodingResult {
        val number = command.precedingDocumentNumber?.takeUnless { it.isBlank() }
        val stated = command.precedingDocumentDate
        val (precedingDate, basis) =
            when {
                stated != null -> {
                    stated to RateDateBasis.PRECEDING_DATE_STATED
                }

                // the caller (canonical-svc) looks the preceding document up (INV-Q-14404 A); a number with no date
                // reaching rules-svc is an unknown original: RUL-008, no coding at any date (INV-AC-1819)
                number != null -> {
                    return CreditNoteCodingResult.UnknownOriginal(number)
                }

                else -> {
                    null to RateDateBasis.OWN_DATE_BLANK_LINKED
                }
            }
        val rateDate = precedingDate ?: command.ownDate
        val trace =
            RateDateTrace(
                ruleId = RULE_ID,
                ownDate = command.ownDate,
                precedingDate = precedingDate,
                rateDate = rateDate,
                basis = basis,
                justificationNote =
                    if (basis == RateDateBasis.OWN_DATE_BLANK_LINKED) {
                        "Rate-change straddle considered: no preceding document stated, resolved by own date " +
                            "${command.ownDate}"
                    } else {
                        null
                    },
            )
        return when (val result = coder.code(CodeDocumentCommand(command.tenant, rateDate, command.lines))) {
            is DocumentCodingResult.Coded -> CreditNoteCodingResult.Coded(result.document, trace)
            else -> CreditNoteCodingResult.NotCoded(result, trace)
        }
    }

    private companion object {
        const val RULE_ID = "FR-AA.1"
    }
}
