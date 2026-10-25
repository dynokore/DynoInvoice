package com.dynokore.gst.reconciliation.domain.variance

import com.dynokore.gst.reconciliation.domain.variance.VarianceClass.BUYER_REJECTED
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass.ERROR_CORRECTED_VIA_F5
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass.EXCLUDED_TXN
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass.INPUT_TAX_NOT_CLAIMED
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass.NO_INVOICE_REQUIRED
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass.OUTSIDE_BY_RULE
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass.PRE_OBLIGATION
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass.TIMING_DIFFERENCE

/** Derivation and assignment rules of [VarianceClass] (INV-Q-7237 A). Pure: no I/O, no clock. */
object VarianceClasses {
    /** Classes derived from a document version (OUTSIDE_BY_RULE is also assignable by a person). */
    val derived: Set<VarianceClass> = setOf(EXCLUDED_TXN, PRE_OBLIGATION, OUTSIDE_BY_RULE, BUYER_REJECTED)

    /** Classes a person may assign through annotateVariance. */
    val assignable: Set<VarianceClass> =
        setOf(TIMING_DIFFERENCE, NO_INVOICE_REQUIRED, INPUT_TAX_NOT_CLAIMED, ERROR_CORRECTED_VIA_F5, OUTSIDE_BY_RULE)

    /**
     * The class the scope verdict the Scope pack gave settles, or null: the amount then stays in the unexplained
     * residual and is never guessed into an expected category. Exact, case-sensitive, untrimmed string equality.
     */
    fun derive(facts: DocumentFacts): VarianceClass? =
        when (facts.scopeVerdict) {
            "EXCLUDED_TXN" -> EXCLUDED_TXN
            "PRE_OBLIGATION" -> PRE_OBLIGATION
            "OUT_OF_SCOPE" -> OUTSIDE_BY_RULE
            "NOT_SUBMITTABLE" -> if (facts.notSubmittableReason == "BUYER_REJECTED") BUYER_REJECTED else null
            else -> null
        }

    /** The member whose name equals [raw] when it is assignable; null for every other string and for null. */
    fun parseAssignable(raw: String?): VarianceClass? = assignable.firstOrNull { it.name == raw }
}
