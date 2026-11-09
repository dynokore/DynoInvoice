package com.dynokore.gst.rules.domain.classification

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.rulepackkernel.evaluation.AppliedPack
import java.math.BigDecimal

/**
 * The function vocabulary of rules-svc, equal to `EvaluateDocumentRequest.documentFunction` of rules/openapi.yaml:
 * `upstreamFunction` and `documentFunction` of the classification table. The mapping of the caller's six values into
 * it belongs to canonical-svc (INV-Q-14800 A); rules-svc holds no second copy.
 */
enum class DocumentFunction { BILL_FOR_PAYMENT, ADJUSTMENT, NOT_A_BILL }

enum class SignOfTotal {
    POSITIVE,
    NEGATIVE,
    ZERO,
    ;

    companion object {
        fun of(amount: BigDecimal): SignOfTotal =
            when (amount.signum()) {
                1 -> POSITIVE
                -1 -> NEGATIVE
                else -> ZERO
            }
    }
}

/** One row of the scope pack classification table (INV-REQ-658). */
data class ClassificationRow(
    val upstreamFunction: DocumentFunction,
    val upstreamKind: String,
    val signOfTotal: SignOfTotal,
    val documentFunction: DocumentFunction,
    /** 380 or 381; null exactly when [documentFunction] is [DocumentFunction.NOT_A_BILL]. */
    val documentTypeCode: String?,
    val signNormalised: Boolean,
    val provisional: Boolean,
    val provisionalReference: String?,
    val cancellation: Boolean,
) {
    val key: String get() = "$upstreamFunction/$upstreamKind/$signOfTotal"
}

/** The table of the scope pack version in force on the date, with the version that holds it. */
data class ClassificationTable(
    val rows: List<ClassificationRow>,
    val applied: AppliedPack,
) {
    /** Exact equality on all three key parts: a look-alike kind (other case, padding) never matches. */
    fun rowOf(
        function: DocumentFunction,
        kind: String,
        sign: SignOfTotal,
    ): ClassificationRow? =
        rows.firstOrNull { it.upstreamFunction == function && it.upstreamKind == kind && it.signOfTotal == sign }
}

/** One line of the document: only the sign of its net amount is read. */
data class ClassifiedLine(
    val lineId: String,
    val amount: BigDecimal,
) {
    companion object {
        /** A line whose magnitude is not carried (the request has `lineSign`, no amount); no rule reads it. */
        fun ofSign(
            lineId: String,
            sign: SignOfTotal,
        ) = ClassifiedLine(
            lineId,
            when (sign) {
                SignOfTotal.POSITIVE -> BigDecimal.ONE
                SignOfTotal.NEGATIVE -> BigDecimal.ONE.negate()
                SignOfTotal.ZERO -> BigDecimal.ZERO
            },
        )
    }
}

data class ClassifyInput(
    val upstreamFunction: DocumentFunction,
    val documentKind: String,
    val lines: List<ClassifiedLine>,
    val precedingDocumentNumber: String? = null,
    val precedingDocumentDate: TransactionDate? = null,
)

/** How an invoice-data document is submitted (INV-REQ-373). */
enum class SubmissionMethod { NETWORK_OR_EXTRACTED, SOLUTION_EXTRACTED_ONLY }

/** The application of one table row, recorded in the decision trace (INV-REQ-658 b2). */
data class ClassificationTraceEntry(
    val ruleId: String,
    val tableKey: String,
    val upstreamFunction: DocumentFunction,
    val upstreamKind: String,
    val signOfTotal: SignOfTotal,
    val documentFunction: DocumentFunction,
    val documentTypeCode: String?,
    val signNormalised: Boolean,
    val provisionalReference: String?,
    val applied: AppliedPack,
)

sealed interface Classification {
    /** An invoice-data document of type 380 or 381. [submissionMethod] is SOLUTION_EXTRACTED_ONLY for a debit note. */
    data class InvoiceData(
        val documentFunction: DocumentFunction,
        val documentTypeCode: String,
        val signNormalised: Boolean,
        val cancellation: Boolean,
        val debitNote: Boolean,
        val submissionMethod: SubmissionMethod,
        val precedingDocumentNumber: String?,
        val precedingDocumentDate: TransactionDate?,
        val trace: ClassificationTraceEntry,
    ) : Classification {
        /** True when PAY-009 must check that the document tallies with its original (INV-REQ-659). */
        val checkAgainstOriginal: Boolean get() = cancellation
    }

    /** Retained and never a submission trigger (INV-REQ-371 b3). [trace] is null when no table row was applied. */
    data class NotABill(
        val trace: ClassificationTraceEntry?,
    ) : Classification

    /** Lines of mixed sign: held with ING-015, never split (INV-REQ-660). */
    data class HeldMixedSign(
        val code: ErrorCode,
        val positiveLines: List<String>,
        val negativeLines: List<String>,
    ) : Classification
}

/** The result for a posting with no source document (INV-REQ-372). */
sealed interface JournalClassification {
    /** Invoice data dated by the posting date; the mapping of the tax code decides its scope. */
    data class Supply(
        val documentFunction: DocumentFunction,
        val transactionDate: TransactionDate,
    ) : JournalClassification

    /** A posting without a tax code is not a supply. */
    data object NotASupply : JournalClassification
}

data class JournalPosting(
    val postingDate: TransactionDate,
    val internalTaxCode: String?,
)

/** Classification by function through the scope pack table (INV-REQ-371, INV-REQ-379, INV-REQ-658 to 660). Pure. */
object DocumentClassifier {
    const val RULE_ID = "FR-AE.61"

    /** Null when the three-part key equals no row and the input is not an asserted NOT_A_BILL (INV-Q-14802 A). */
    fun classify(
        input: ClassifyInput,
        table: ClassificationTable,
    ): Classification? = heldIfMixed(input) ?: classifyByTable(input, table)

    fun signOfTotal(input: ClassifyInput): SignOfTotal =
        SignOfTotal.of(input.lines.fold(BigDecimal.ZERO) { sum, line -> sum + line.amount })

    private fun heldIfMixed(input: ClassifyInput): Classification.HeldMixedSign? {
        val positive = input.lines.filter { it.amount.signum() > 0 }.map { it.lineId }
        val negative = input.lines.filter { it.amount.signum() < 0 }.map { it.lineId }
        return if (positive.isNotEmpty() && negative.isNotEmpty()) {
            Classification.HeldMixedSign(ErrorCode.ING_015, positive, negative)
        } else {
            null
        }
    }

    private fun classifyByTable(
        input: ClassifyInput,
        table: ClassificationTable,
    ): Classification? {
        val sign = signOfTotal(input)
        val upstream = input.upstreamFunction
        val row = table.rowOf(upstream, input.documentKind, sign)
        if (row == null) {
            // An asserted NOT_A_BILL no row contradicts is kept and never submitted; anything else the table does
            // not settle has no type code (no default).
            return if (upstream == DocumentFunction.NOT_A_BILL) Classification.NotABill(null) else null
        }
        val trace = traceOf(row, sign, table.applied)
        val code = row.documentTypeCode
        return if (row.documentFunction == DocumentFunction.NOT_A_BILL || code == null) {
            Classification.NotABill(trace)
        } else {
            val debit = row.documentFunction == DocumentFunction.ADJUSTMENT && code == DEBIT_NOTE_CODE
            Classification.InvoiceData(
                documentFunction = row.documentFunction,
                documentTypeCode = code,
                signNormalised = row.signNormalised,
                cancellation = row.cancellation,
                debitNote = debit,
                submissionMethod =
                    if (debit) SubmissionMethod.SOLUTION_EXTRACTED_ONLY else SubmissionMethod.NETWORK_OR_EXTRACTED,
                precedingDocumentNumber = input.precedingDocumentNumber,
                precedingDocumentDate = input.precedingDocumentDate,
                trace = trace,
            )
        }
    }

    /** INV-REQ-372: a posting with an internal tax code is invoice data dated by its posting date. */
    fun classifyJournal(posting: JournalPosting): JournalClassification =
        if (posting.internalTaxCode.isNullOrBlank()) {
            JournalClassification.NotASupply
        } else {
            JournalClassification.Supply(DocumentFunction.BILL_FOR_PAYMENT, posting.postingDate)
        }

    private fun traceOf(
        row: ClassificationRow,
        sign: SignOfTotal,
        applied: AppliedPack,
    ) = ClassificationTraceEntry(
        ruleId = RULE_ID,
        tableKey = row.key,
        upstreamFunction = row.upstreamFunction,
        upstreamKind = row.upstreamKind,
        signOfTotal = sign,
        documentFunction = row.documentFunction,
        documentTypeCode = row.documentTypeCode,
        signNormalised = row.signNormalised,
        provisionalReference = row.provisionalReference,
        applied = applied,
    )

    private const val DEBIT_NOTE_CODE = "380"
}
