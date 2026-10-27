package com.dynokore.gst.submission.domain.migration

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import java.time.LocalDate

/** Why a document is held for manual handling under SUB-017 (INV-Q-6887 A). */
enum class HoldReason { RESUBMISSION_OF_EXTERNALLY_SUBMITTED, CORRECTION_OF_PRE_CUTOVER_DOCUMENT }

/**
 * One document of the ERP request as the guard sees it.
 *
 * @property documentNumber absent or blank means the document has no number to match on.
 * @property creditNote true when `documentFunction` is CREDIT_NOTE; the mapping from the request is the caller's.
 * @property versions the lifecycle states of the document's versions in version order, empty on a first arrival.
 */
data class PriorSolutionDocument(
    val entityRef: String,
    val documentNumber: String?,
    val transactionDate: LocalDate,
    val creditNote: Boolean,
    val precedingDocumentNumber: String?,
    val precedingDocumentDate: LocalDate?,
    val versions: List<LifecycleState>,
) {
    init {
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
    }
}

/** One record of prior submission evidence imported from the prior solution (INV-REQ-1109). */
data class ImportedPriorRecord(
    val entityRef: String,
    val documentNumber: String,
    val transactionDate: LocalDate,
    val acknowledgementRef: String,
) {
    init {
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(documentNumber.isNotBlank()) { "documentNumber must not be blank" }
        require(acknowledgementRef.isNotBlank()) { "acknowledgementRef must not be blank" }
    }
}

/** The decision of [PriorSolutionGuard.decide]. */
sealed interface PriorSolutionDecision {
    data object NotAffected : PriorSolutionDecision

    data class ExternallySubmitted(
        val acknowledgementRef: String,
    ) : PriorSolutionDecision

    data class HoldForCorrection(
        val code: String,
        val reason: HoldReason,
    ) : PriorSolutionDecision
}

/** Suppression of a prior-solution document and hold of a pre-cutover correction (INV-REQ-1109, INV-REQ-1110). */
object PriorSolutionGuard {
    /** The catalogue code of catalogue/errors/error-codes.yaml, BLOCK, MANUAL, owner Tax operations. */
    const val SUB_017 = "SUB-017"

    private fun hold(reason: HoldReason) = PriorSolutionDecision.HoldForCorrection(SUB_017, reason)

    /** Pure and total over valid input; reads no clock and no store. */
    fun decide(
        document: PriorSolutionDocument,
        cutoverDate: LocalDate?,
        imported: List<ImportedPriorRecord>,
    ): PriorSolutionDecision {
        requireConsistent(imported)
        return when {
            // Rule 1: a resubmission of an externally submitted document, whatever the other inputs.
            LifecycleState.EXTERNALLY_SUBMITTED in document.versions -> {
                hold(
                    HoldReason.RESUBMISSION_OF_EXTERNALLY_SUBMITTED,
                )
            }

            cutoverDate == null -> {
                PriorSolutionDecision.NotAffected
            }

            else -> {
                matchOf(document, cutoverDate, imported) ?: correctionOf(document, cutoverDate, imported)
            }
        }
    }

    private fun requireConsistent(imported: List<ImportedPriorRecord>) {
        imported.groupBy { Triple(it.entityRef, it.documentNumber, it.transactionDate) }.forEach { (key, records) ->
            require(records.map { it.acknowledgementRef }.distinct().size == 1) {
                "imported records of $key carry different acknowledgementRef"
            }
        }
    }

    /** Rule 2: a pre-cutover document that matches imported evidence. */
    private fun matchOf(
        document: PriorSolutionDocument,
        cutoverDate: LocalDate,
        imported: List<ImportedPriorRecord>,
    ): PriorSolutionDecision? {
        val number = document.documentNumber
        if (number.isNullOrBlank() || !document.transactionDate.isBefore(cutoverDate)) return null
        return imported
            .firstOrNull {
                it.entityRef == document.entityRef &&
                    it.documentNumber == number &&
                    it.transactionDate == document.transactionDate
            }?.let { PriorSolutionDecision.ExternallySubmitted(it.acknowledgementRef) }
    }

    /** Rules 3 and 4: a credit note referring to a pre-cutover document is held, anything else is not affected. */
    private fun correctionOf(
        document: PriorSolutionDocument,
        cutoverDate: LocalDate,
        imported: List<ImportedPriorRecord>,
    ): PriorSolutionDecision {
        val precedingDate = document.precedingDocumentDate
        val precedingNumber = document.precedingDocumentNumber
        val refersToPreCutover =
            document.creditNote &&
                if (precedingDate != null) {
                    precedingDate.isBefore(cutoverDate)
                } else {
                    precedingNumber != null &&
                        imported.any {
                            it.entityRef == document.entityRef &&
                                it.documentNumber == precedingNumber &&
                                it.transactionDate.isBefore(cutoverDate)
                        }
                }
        return if (refersToPreCutover) {
            hold(
                HoldReason.CORRECTION_OF_PRE_CUTOVER_DOCUMENT,
            )
        } else {
            PriorSolutionDecision.NotAffected
        }
    }
}
