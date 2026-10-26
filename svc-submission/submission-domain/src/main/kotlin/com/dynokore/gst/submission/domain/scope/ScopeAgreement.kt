package com.dynokore.gst.submission.domain.scope

/** The `scopeVerdict` members of canonical/openapi.yaml `RecordDocumentResponse`. */
enum class ScopeVerdict { SUBMITTABLE, NOT_SUBMITTABLE, PRE_OBLIGATION }

/** The `notSubmittableReason` members of canonical/openapi.yaml `RecordDocumentResponse`. */
enum class NotSubmittableReason { BUYER_REJECTED, ISSUANCE_REFUSED, NETTED_INTO_REVISION }

/** One document's scope verdict as canonical-svc reports it; a reason is only valid on NOT_SUBMITTABLE. */
data class Verdict(
    val scopeVerdict: ScopeVerdict,
    val notSubmittableReason: NotSubmittableReason? = null,
) {
    init {
        require(notSubmittableReason == null || scopeVerdict == ScopeVerdict.NOT_SUBMITTABLE) {
            "notSubmittableReason is set only when scopeVerdict is NOT_SUBMITTABLE"
        }
    }
}

/** The catalogue code a disagreement raises (catalogue/errors/error-codes.yaml). */
enum class ScopeCode(
    val code: String,
) {
    SUB_013("SUB-013"),
}

/** Why two verdicts are not compared. */
enum class Why { NO_PRECEDING, CREDIT_NOTE_SIDE, PRECEDING_SIDE, BOTH_SIDES }

/** The decision of [ScopeAgreement.decide]; no member carries a replacement verdict (INV-REQ-690). */
sealed interface ScopeDecision {
    data object Agree : ScopeDecision

    data class Disagree(
        val code: ScopeCode,
        val creditNote: Verdict,
        val preceding: Verdict,
    ) : ScopeDecision

    data class NotComparable(
        val why: Why,
    ) : ScopeDecision
}

/** Whether a credit note and its preceding document disagree on scope (INV-REQ-722, INV-Q-6618); resolves nothing. */
object ScopeAgreement {
    private enum class Side { IN, OUT, NOT_COMPARABLE }

    private fun side(verdict: Verdict): Side =
        when (verdict.scopeVerdict) {
            ScopeVerdict.SUBMITTABLE -> Side.IN
            ScopeVerdict.NOT_SUBMITTABLE -> if (verdict.notSubmittableReason == null) Side.OUT else Side.NOT_COMPARABLE
            ScopeVerdict.PRE_OBLIGATION -> Side.NOT_COMPARABLE
        }

    fun decide(
        creditNote: Verdict,
        preceding: Verdict?,
    ): ScopeDecision {
        if (preceding == null) return ScopeDecision.NotComparable(Why.NO_PRECEDING)
        val own = side(creditNote)
        val other = side(preceding)
        val noteNotCompared = own == Side.NOT_COMPARABLE
        val precedingNotCompared = other == Side.NOT_COMPARABLE
        return when {
            noteNotCompared && precedingNotCompared -> ScopeDecision.NotComparable(Why.BOTH_SIDES)
            noteNotCompared -> ScopeDecision.NotComparable(Why.CREDIT_NOTE_SIDE)
            precedingNotCompared -> ScopeDecision.NotComparable(Why.PRECEDING_SIDE)
            own == other -> ScopeDecision.Agree
            else -> ScopeDecision.Disagree(ScopeCode.SUB_013, creditNote, preceding)
        }
    }
}
