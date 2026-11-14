package com.dynokore.gst.submission.adapters.http

import com.dynokore.gst.submission.application.listing.SubmissionSummaryQuery
import com.dynokore.gst.submission.application.listing.SubmissionSummaryRequest
import com.dynokore.gst.submission.application.listing.SubmissionSummaryResult
import com.dynokore.gst.submission.contract.model.SubmissionSummaryTotals
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.listing.SubmissionFilter
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.reconciliation.ReconciliationBucket
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import org.springframework.stereotype.Component
import java.math.BigDecimal
import com.dynokore.gst.submission.contract.model.ReconciliationBucket as ContractBucket

/**
 * The delegate that serves `getSubmissionSummary` for `TransactionsController` (the operation is tagged
 * `transactions`, so its generated interface is already implemented there; INV-REQ-977). It maps the contract
 * parameters, which are the filters of `listSubmissions`, to [SubmissionSummaryQuery] and its result to the contract
 * totals; the permission is decided inside the use case through `AuthorityReplica.decide`. This class reads no role
 * name and holds no permission string (INV-Q-10027). Refusals are thrown as [ComplianceRefusal] and rendered once by
 * `TransactionsRefusalAdvice`.
 */
@Component
class SubmissionSummaryController(
    private val query: SubmissionSummaryQuery,
) {
    @Suppress("LongParameterList") // the generated operation signature
    fun summarise(
        caller: Principal,
        entityRef: String,
        periodRef: String,
        direction: String?,
        submissionType: String?,
        state: String?,
        categoryCode: String?,
        counterparty: String?,
        amountMin: BigDecimal?,
        amountMax: BigDecimal?,
        acknowledged: Boolean?,
        bucket: ContractBucket?,
    ): SubmissionSummaryTotals {
        val tenant = caller.tenant ?: throw ComplianceRefusal(ClosedErrorCode.UNAUTHENTICATED)
        val filter =
            filterOf(
                direction,
                submissionType,
                state,
                categoryCode,
                counterparty,
                amountMin,
                amountMax,
                acknowledged,
                bucket,
            )
        val deadline = CurrentDeadline.current() ?: error("no call deadline is bound")
        return totalsOf(query.summary(SubmissionSummaryRequest(tenant, caller, entityRef, periodRef, filter), deadline))
    }

    private fun totalsOf(result: SubmissionSummaryResult): SubmissionSummaryTotals =
        when (result) {
            is SubmissionSummaryResult.Totals -> {
                SubmissionSummaryTotals(
                    result.totals.rowCount,
                    result.totals.totalTaxableAmount,
                    result.totals.totalGstAmount,
                )
            }

            SubmissionSummaryResult.Forbidden -> {
                throw ComplianceRefusal(ClosedErrorCode.ACCESS_DENIED)
            }

            SubmissionSummaryResult.MaskedFactFilter, SubmissionSummaryResult.BucketNotServed -> {
                throw ComplianceRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
            }
        }

    /** A filter value outside the contract enums or breaking a filter rule is SRT-001 before any read. */
    @Suppress("LongParameterList") // the filters of the contract operation
    private fun filterOf(
        direction: String?,
        submissionType: String?,
        state: String?,
        categoryCode: String?,
        counterparty: String?,
        amountMin: BigDecimal?,
        amountMax: BigDecimal?,
        acknowledged: Boolean?,
        bucket: ContractBucket?,
    ): SubmissionFilter =
        try {
            SubmissionFilter(
                direction = direction?.let { Direction.valueOf(it) },
                submissionType = submissionType?.let { SubmissionKind.valueOf(it) },
                state = state?.let { LifecycleState.valueOf(it) },
                categoryCode = categoryCode,
                counterparty = counterparty,
                amountMin = amountMin,
                amountMax = amountMax,
                acknowledged = acknowledged,
                bucket = bucket?.let { ReconciliationBucket.valueOf(it.name) },
            )
        } catch (_: IllegalArgumentException) {
            throw ComplianceRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
        }
}
