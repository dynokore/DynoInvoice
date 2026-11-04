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

    private fun decide(f: ScopeFacts): ScopeOutcome =
        windowVerdict(f)?.let(::plain)
            ?: (f.line as? LineCoding.Coded)?.let { decideCoded(f, it.categoryCode) }
            ?: plain(ScopeVerdict.NOT_SUBMITTABLE) // step 2: a refused line

    /** Step 1: the registration window; an absent obligation start date fails closed. */
    private fun windowVerdict(f: ScopeFacts): ScopeVerdict? {
        val start = f.obligationStartDate
        val end = f.registrationEndDate
        return when {
            start == null || f.date.value < start.value -> ScopeVerdict.PRE_OBLIGATION
            end != null && f.date.value > end.value -> ScopeVerdict.NOT_SUBMITTABLE
            else -> null
        }
    }

    /** Steps 3 to 7 for a coded line that is inside the window. */
    private fun decideCoded(
        f: ScopeFacts,
        code: String,
    ): ScopeOutcome {
        val lineVerdict = data.lineVerdicts[code]
        return when {
            // Step 3: intra-group treatment other than PASS_THROUGH or absent.
            f.sameGroup && data.intraGroupTreatment != PASS_THROUGH -> {
                plain(ScopeVerdict.NOT_SUBMITTABLE)
            }

            // Step 4: DS is EXCLUDED_TXN in either mode whatever the data says (INV-REQ-493, INV-Q-16736).
            code == "DS" -> {
                plain(ScopeVerdict.EXCLUDED_TXN)
            }

            // Step 4b: a code the data lacks.
            lineVerdict == null -> {
                plain(ScopeVerdict.NOT_SUBMITTABLE)
            }

            else -> {
                ScopeOutcome(
                    overrideVerdict(f, code) ?: lineVerdict,
                    f.sameGroup && lineVerdict == ScopeVerdict.IN_SCOPE,
                )
            }
        }
    }

    /** Step 5 (a purchase with no input tax claimed) and step 6 (OS under voluntary over-submission). */
    private fun overrideVerdict(
        f: ScopeFacts,
        code: String,
    ): ScopeVerdict? =
        when {
            f.direction == ScopeDirection.PURCHASE &&
                code in data.purchaseStandardRatedCodes &&
                f.inputTaxClaimedNonZero == false -> {
                if (f.voluntaryOverSubmission) ScopeVerdict.SUBMITTABLE else ScopeVerdict.OUT_OF_SCOPE
            }

            // INV-REQ-2437 behavior 2: an import permit mapped to ME, IGDS or IM is transmitted only under VP-21 ON
            (code == "OS" || code in IMPORT_PERMIT_CODES) && f.voluntaryOverSubmission -> {
                ScopeVerdict.SUBMITTABLE
            }

            else -> {
                null
            }
        }

    private fun plain(v: ScopeVerdict) = ScopeOutcome(v, disregardedSupplyWarning = false)

    private companion object {
        const val PASS_THROUGH = "PASS_THROUGH"
        val IMPORT_PERMIT_CODES = setOf("ME", "IGDS", "IM")
    }
}
