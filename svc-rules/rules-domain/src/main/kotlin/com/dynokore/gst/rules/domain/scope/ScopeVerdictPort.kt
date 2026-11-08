package com.dynokore.gst.rules.domain.scope

import com.dynokore.gst.rules.domain.coding.LineCoding
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.tenancykernel.TenantId

/** What a scope verdict is decided from. */
data class ScopeFacts(
    val tenant: TenantId?,
    val date: TransactionDate,
    val line: LineCoding,
    val direction: ScopeDirection = ScopeDirection.SALE,
    val sameGroup: Boolean = false,
    val inputTaxClaimedNonZero: Boolean? = null,
    /** True only when the adapter has seen the VP-21 option key exactly `ON`. */
    val voluntaryOverSubmission: Boolean = false,
    /** The profile attributes `obligationStartDate` and `registrationEndDate` (INV-Q-16175). */
    val obligationStartDate: TransactionDate? = null,
    val registrationEndDate: TransactionDate? = null,
    /** The internal tax code the business assigned; the election names it or the mapped IRAS code. */
    val internalTaxCode: String? = null,
    val exemption: PartialExemptionFacts = PartialExemptionFacts(),
)

/** Which side of the books the line is on. */
enum class ScopeDirection { SALE, PURCHASE }

/** The verdict and the disregarded-supply warning (INV-Q-16173) of one line. */
data class ScopeOutcome(
    val verdict: ScopeVerdict,
    val disregardedSupplyWarning: Boolean,
    /** The rules that decided the line with their inputs; empty when only the general rule applied. */
    val trace: List<TraceEntry> = emptyList(),
    /** The category code of an apportioned claimable portion; null when the mapped code stands. */
    val categoryCode: String? = null,
)

/** The scope rules, supplied from outside; the use case judges nothing and returns the answer as is. */
interface ScopeVerdictPort {
    fun verdictOf(facts: ScopeFacts): ScopeVerdict

    fun outcomeOf(facts: ScopeFacts): ScopeOutcome = ScopeOutcome(verdictOf(facts), disregardedSupplyWarning = false)
}
