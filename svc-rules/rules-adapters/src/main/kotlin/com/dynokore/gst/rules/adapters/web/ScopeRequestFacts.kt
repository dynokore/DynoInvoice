package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.adapters.scope.ProfileDates
import com.dynokore.gst.rules.contract.model.EvaluateDocumentRequest
import com.dynokore.gst.rules.domain.coding.LineCoding
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.gst.rules.domain.scope.ApportionmentMode
import com.dynokore.gst.rules.domain.scope.PartialExemptionFacts
import com.dynokore.gst.rules.domain.scope.ScopeDirection
import com.dynokore.gst.rules.domain.scope.ScopeFacts
import com.dynokore.gst.rules.domain.scope.ScopeOutcome
import com.dynokore.platform.tenancykernel.TenantId

/**
 * The assembly of the scope facts from the request and the replica reads, and the turning of the scope outcome into
 * the answer, as pure functions (INV-REQ-382, INV-REQ-370). Nothing is read here and no amount is carried.
 */
object ScopeRequestFacts {
    const val SCOPE_RULES = "scopeRules"

    /**
     * `internalTaxCode` is the request's, unchanged (no trimming). [recorded] is the entity's status and election read
     * from the replica for the transaction date; the mode and the mixed-use indicator are the request's, null when
     * absent.
     */
    @Suppress("LongParameterList") // the facts of one line come from the request and four replica reads
    fun factsOf(
        request: EvaluateDocumentRequest,
        tenant: TenantId,
        date: TransactionDate,
        coding: LineCoding,
        dates: ProfileDates,
        recorded: PartialExemptionFacts,
        voluntary: Boolean,
    ): ScopeFacts =
        ScopeFacts(
            tenant = tenant,
            date = date,
            line = coding,
            direction = request.direction?.let { ScopeDirection.valueOf(it.value) } ?: ScopeDirection.SALE,
            sameGroup = request.sameGroup ?: false,
            inputTaxClaimedNonZero = request.inputTaxClaimedNonZero,
            voluntaryOverSubmission = voluntary,
            obligationStartDate = dates.obligationStartDate,
            registrationEndDate = dates.registrationEndDate,
            internalTaxCode = request.internalTaxCode,
            exemption =
                recorded.copy(
                    apportionmentMode = request.apportionmentMode?.let { ApportionmentMode.valueOf(it.name) },
                    mixedUse = request.mixedUse?.let { ApportionmentMode.valueOf(it.name) },
                ),
        )

    /** The outcome's apportioned code when it has one, else the mapped code. */
    fun categoryCodeOf(
        outcome: ScopeOutcome,
        mapped: String,
    ): String = outcome.categoryCode ?: mapped

    /**
     * [base] keeps every key it has; `scopeRules` is added, in the outcome's order, only when the outcome has trace
     * entries. A `scopeRules` key already in [base] is an error: it is never overwritten silently.
     */
    fun traceWith(
        base: Map<String, Any>?,
        outcome: ScopeOutcome,
    ): Map<String, Any>? {
        if (outcome.trace.isEmpty()) return base
        check(base == null || SCOPE_RULES !in base) { "the line trace already holds the key $SCOPE_RULES" }
        val rules = outcome.trace.map { mapOf("rule" to it.rule, "inputs" to it.inputs) }
        return base.orEmpty() + (SCOPE_RULES to rules)
    }
}
