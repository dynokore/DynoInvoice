package com.dynokore.gst.rules.domain.scope

import com.dynokore.gst.rules.domain.coding.LineCoding

/**
 * The pure data of the scope rules: the `lineVerdict` per category code of the scope pack, the intra-group `treatment`
 * (null when the pack has none) and the category codes of the code catalogue entries in group PURCHASE_STANDARD_RATED.
 */
data class ScopeRulesData(
    val lineVerdicts: Map<String, ScopeVerdict>,
    val intraGroupTreatment: String?,
    val purchaseStandardRatedCodes: Set<String>,
)

/**
 * The scope rules as pure code (INV-REQ-376, INV-REQ-377, INV-REQ-378, INV-REQ-738, INV-REQ-720). Deny by default:
 * anything the data does not name is NOT_SUBMITTABLE. The first matching step wins.
 */
class ScopeRules(
    private val data: ScopeRulesData,
) : ScopeVerdictPort {
    override fun verdictOf(facts: ScopeFacts): ScopeVerdict = decide(facts).verdict

    override fun outcomeOf(facts: ScopeFacts): ScopeOutcome = decide(facts)

    private fun decide(f: ScopeFacts): ScopeOutcome {
        // Step 1: the registration window; an absent obligation start date fails closed.
        val start = f.obligationStartDate ?: return plain(ScopeVerdict.PRE_OBLIGATION)
        if (f.date.value < start.value) return plain(ScopeVerdict.PRE_OBLIGATION)
        val end = f.registrationEndDate
        if (end != null && f.date.value > end.value) return plain(ScopeVerdict.NOT_SUBMITTABLE)
        // Step 2: a refused line.
        val line = f.line as? LineCoding.Coded ?: return plain(ScopeVerdict.NOT_SUBMITTABLE)
        // Step 3: intra-group treatment other than PASS_THROUGH, or absent.
        if (f.sameGroup && data.intraGroupTreatment != PASS_THROUGH) return plain(ScopeVerdict.NOT_SUBMITTABLE)
        val code = line.categoryCode
        // Step 4: DS, and a code the data does not know.
        if (code == "DS") return plain(ScopeVerdict.NOT_SUBMITTABLE)
        val lineVerdict = data.lineVerdicts[code] ?: return plain(ScopeVerdict.NOT_SUBMITTABLE)
        val warning = f.sameGroup && lineVerdict == ScopeVerdict.IN_SCOPE
        // Step 5: a purchase of a standard-rated code with no input tax claimed.
        if (f.direction == ScopeDirection.PURCHASE &&
            code in data.purchaseStandardRatedCodes &&
            f.inputTaxClaimedNonZero == false
        ) {
            val v = if (f.voluntaryOverSubmission) ScopeVerdict.SUBMITTABLE else ScopeVerdict.OUT_OF_SCOPE
            return ScopeOutcome(v, warning)
        }
        // Step 6: OS under voluntary over-submission.
        if (code == "OS" && f.voluntaryOverSubmission) return ScopeOutcome(ScopeVerdict.SUBMITTABLE, warning)
        // Step 7: the pack's line verdict.
        return ScopeOutcome(lineVerdict, warning)
    }

    private fun plain(v: ScopeVerdict) = ScopeOutcome(v, disregardedSupplyWarning = false)

    private companion object {
        const val PASS_THROUGH = "PASS_THROUGH"
    }
}
