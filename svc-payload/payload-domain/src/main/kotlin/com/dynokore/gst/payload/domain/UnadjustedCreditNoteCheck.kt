package com.dynokore.gst.payload.domain

/** One line of a credit note with its GST category code. */
data class LineCode(
    val lineNumber: Int,
    val categoryCode: String,
)

sealed interface CheckResult {
    data object Passes : CheckResult

    data class Violations(
        val list: List<LineCode>,
    ) : CheckResult
}

/** The IRAS category code a credit note issued without GST adjustment keeps (INV-REQ-693). */
const val IRAS_STANDARD_RATED_CODE = "SR"

/** The category-code check of a credit note issued without GST adjustment (INV-REQ-693, INV-Q-6639 A), pure. */
object UnadjustedCreditNoteCheck {
    fun check(
        marked: Boolean,
        lines: List<LineCode>,
    ): CheckResult {
        if (!marked) return CheckResult.Passes
        val bad = lines.filter { it.categoryCode != IRAS_STANDARD_RATED_CODE }
        return if (bad.isEmpty()) CheckResult.Passes else CheckResult.Violations(bad)
    }
}
