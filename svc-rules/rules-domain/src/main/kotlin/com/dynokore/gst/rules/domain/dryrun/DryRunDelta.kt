package com.dynokore.gst.rules.domain.dryrun

import java.math.BigDecimal
import java.time.LocalDate

/** The three change types of a changed document, in the order of `changeTypeTotals` (INV-REQ-1020, INV-Q-6283 A). */
enum class ChangeType { CODE_CHANGED, NEWLY_REFUSED, NEWLY_ACCEPTED }

/**
 * The outcome of one evaluation: the category code, or, when the evaluation refuses, its catalogue code and severity.
 * A refusal is a severity of BLOCK or HOLD; WARN is not a refusal.
 */
data class DocumentOutcome(
    val code: String,
    val severity: String? = null,
) {
    val refused: Boolean get() = severity == "BLOCK" || severity == "HOLD"
}

/** One document of a sample page, read from its page item. The two values are never read for a flagged item. */
data class SampledDocument(
    val pageNumber: Int,
    val position: Int,
    val docUuid: String,
    val periodRef: String,
    val entityRef: String,
    val transactionDate: LocalDate,
    val internalTaxCode: String,
    val documentFunction: String,
    val precedingDocumentDate: LocalDate?,
    val supplyType: String?,
    val sgdValuesAbsent: Boolean,
    val taxableValue: BigDecimal,
    val gstValue: BigDecimal,
)

/** A changed document with its old and new outcome; money is `0.00` for a flagged document and the page value otherwise. */
data class ChangedDocument(
    val docUuid: String,
    val periodRef: String,
    val transactionDate: LocalDate,
    val precedingDocumentDate: LocalDate?,
    val documentFunction: String,
    val supplyType: String?,
    val oldOutcome: DocumentOutcome,
    val newOutcome: DocumentOutcome,
    val changeType: ChangeType,
    val taxableValue: String,
    val gstValue: String,
    val sgdValuesAbsent: Boolean,
)

/** An item of `DryRunResult.sample` or `straddlingCreditNotes`; `sgdValuesAbsent` is true or null, never false. */
data class DeltaListing(
    val docUuid: String,
    val transactionDate: LocalDate,
    val precedingDocumentDate: LocalDate?,
    val oldCategoryCode: String,
    val newCategoryCode: String,
    val taxableValue: String,
    val gstValue: String,
    val supplyType: String?,
    val sgdValuesAbsent: Boolean?,
)

data class ChangeTypeTotal(
    val changeType: ChangeType,
    val documentCount: Int,
    val taxableValue: String,
    val gstValue: String,
    val valuesAbsentCount: Int,
)

data class PeriodDelta(
    val periodRef: String,
    val documentCount: Int,
    val taxableValue: String,
    val gstValue: String,
    val valuesAbsentCount: Int,
)

/** The delta of one dry run (INV-REQ-772, INV-REQ-686, INV-REQ-703); its members are those of `DryRunResult`. */
data class DryRunDelta(
    val changedCount: Int,
    val documents: List<ChangedDocument>,
    val changeTypeTotals: List<ChangeTypeTotal>,
    val byPeriod: List<PeriodDelta>,
    val straddlingCreditNotes: List<DeltaListing>,
    val sample: List<DeltaListing>,
)
