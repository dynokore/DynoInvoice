package com.dynokore.platform.rulesevaluation.scope

/** The apportionment submission mode of an entity, and the mixed-use indicator of a line (INV-REQ-370). */
enum class ApportionmentMode { CLAIMABLE_PORTION, ENTIRE_INVOICE }

/**
 * The recorded exempt-purchase election of INV-REQ-369: the tax codes it names (internal or IRAS codes, compared by
 * equality) and the basis of certainty. A blank basis is no election, so it cannot be built (fail closed).
 */
data class ExemptElection(
    val codes: Set<String>,
    val basis: String,
) {
    init {
        require(basis.isNotBlank()) { "an election records a non-blank basis of certainty" }
        require(codes.isNotEmpty()) { "an election names at least one tax code" }
    }

    fun names(vararg candidates: String?): Boolean = candidates.any { it != null && it in codes }
}

/** The recorded per-entity facts the partial-exemption and apportionment treatment reads (INV-REQ-382, 370). */
data class PartialExemptionFacts(
    /** True only for the recorded status `true` on the transaction date; never inferred from a code. */
    val partiallyExempt: Boolean = false,
    val election: ExemptElection? = null,
    /** Supplied by the caller for the transaction date; null means no election is recorded. */
    val apportionmentMode: ApportionmentMode? = null,
    val mixedUse: ApportionmentMode? = null,
)

/** One rule that took part in the decision, with the inputs it names (the decision trace). */
data class TraceEntry(
    val rule: String,
    val inputs: Map<String, String>,
)

/** Trace rule names and the codes of this treatment. */
object PartialExemption {
    const val RULE_NON_REPORTABLE = "NON_REPORTABLE_CODE"
    const val RULE_PARTIAL_EXEMPTION = "PARTIAL_EXEMPTION"
    const val RULE_EXEMPT_ELECTION = "EXEMPT_PURCHASE_ELECTION"
    const val RULE_APPORTIONMENT = "APPORTIONMENT_CLAIMABLE_PORTION"

    /** R1 Annex E 'Disallowed expenses': a non-reportable purchase code, out of scope for every entity. */
    const val DISALLOWED = "BL"

    /** The category code of the claimable portion of a mixed-use purchase (INV-REQ-370 behavior 3). */
    const val CLAIMABLE_PORTION_CODE = "TX"

    /** The code the claimable portion is submitted with, or null when the line is not apportioned. */
    fun apportionedCode(
        direction: ScopeDirection,
        facts: PartialExemptionFacts,
    ): String? =
        if (direction == ScopeDirection.PURCHASE &&
            facts.apportionmentMode == ApportionmentMode.CLAIMABLE_PORTION &&
            facts.mixedUse == ApportionmentMode.CLAIMABLE_PORTION
        ) {
            CLAIMABLE_PORTION_CODE
        } else {
            null
        }
}
