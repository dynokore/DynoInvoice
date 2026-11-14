package com.dynokore.gst.submission.application.listing

import com.dynokore.gst.submission.domain.listing.SubmissionFilter
import com.dynokore.gst.submission.domain.listing.SubmissionTotals
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId

/** One `getSubmissionSummary` call: the filter is the one of `listSubmissions`. */
data class SubmissionSummaryRequest(
    val tenant: TenantId,
    val principal: Principal,
    val entityRef: String,
    val periodRef: String,
    val filter: SubmissionFilter,
)

sealed interface SubmissionSummaryResult {
    data class Totals(
        val totals: SubmissionTotals,
    ) : SubmissionSummaryResult

    data object Forbidden : SubmissionSummaryResult

    data object MaskedFactFilter : SubmissionSummaryResult

    data object BucketNotServed : SubmissionSummaryResult
}

/**
 * The summary of the submission list (INV-REQ-977, INV-AC-22164): the row count and the two totals of exactly the rows
 * the list returns for the same filter. The rows come from the one [SubmissionRowSource], the selection is
 * [SubmissionFilter.select] over [documentBuckets], and the totals are [SubmissionTotals.of]. The permission is decided
 * per request through [AuthorityReplica.decide] (`machine.status.read` for a MACHINE, `submission.view-status`
 * otherwise); a denial, a masked-fact filter without the content key and an unserved bucket read nothing. No role name
 * is read (INV-Q-10027).
 */
class SubmissionSummaryQuery(
    private val replica: AuthorityReplica,
    private val source: SubmissionRowSource,
) {
    fun summary(
        request: SubmissionSummaryRequest,
        deadline: Deadline,
    ): SubmissionSummaryResult {
        val viewKey =
            if (request.principal.kind == PrincipalKind.MACHINE) {
                ListingActions.VIEW_SUMMARY_MACHINE
            } else {
                ListingActions.VIEW_STATUS
            }
        return when {
            !decide(request, viewKey, deadline) -> {
                SubmissionSummaryResult.Forbidden
            }

            touchesMaskedFact(request.filter) && !decide(request, ListingActions.VIEW_CONTENT, deadline) -> {
                SubmissionSummaryResult.MaskedFactFilter
            }

            request.filter.bucket != null && request.filter.bucket !in SERVED_BUCKETS -> {
                SubmissionSummaryResult.BucketNotServed
            }

            else -> {
                val rows = source.rows(request.tenant, request.entityRef, request.periodRef).rows
                val selected = request.filter.select(rows, documentBuckets(rows)::get)
                SubmissionSummaryResult.Totals(SubmissionTotals.of(selected))
            }
        }
    }

    private fun decide(
        request: SubmissionSummaryRequest,
        action: String,
        deadline: Deadline,
    ): Boolean =
        replica
            .decide(AuthorizationRequest(request.tenant, request.principal, request.entityRef, action), deadline)
            .allowed
}
