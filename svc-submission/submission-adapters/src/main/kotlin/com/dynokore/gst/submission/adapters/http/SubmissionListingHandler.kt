package com.dynokore.gst.submission.adapters.http

import com.dynokore.gst.submission.application.listing.ListSubmissions
import com.dynokore.gst.submission.application.listing.ListSubmissionsRequest
import com.dynokore.gst.submission.application.listing.ListSubmissionsResult
import com.dynokore.gst.submission.application.listing.ListSubmissionsRow
import com.dynokore.gst.submission.contract.model.SubmissionPage
import com.dynokore.gst.submission.contract.model.SubmissionSummary
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.listing.SubmissionFilter
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.reconciliation.ReconciliationBucket
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset
import com.dynokore.gst.submission.contract.model.ReconciliationBucket as ContractBucket

/** The filters, sort and paging of one `listSubmissions` call, as the contract names them. */
data class ListingQuery(
    val entityRef: String,
    val periodRef: String,
    val direction: String?,
    val submissionType: String?,
    val state: String?,
    val categoryCode: String?,
    val counterparty: String?,
    val amountMin: BigDecimal?,
    val amountMax: BigDecimal?,
    val acknowledged: Boolean?,
    val bucket: ContractBucket?,
    val sort: String?,
    val limit: Int,
    val cursor: String?,
)

/**
 * The delegate that serves `listSubmissions` for `TransactionsController` (the operation is tagged `transactions`,
 * so its generated interface is already implemented there). It maps the contract parameters to the use case and its
 * result to the contract page; the permission is decided inside [ListSubmissions] through `AuthorityReplica.decide`,
 * so this class reads no role name and holds no permission string (INV-Q-10027). Refusals are thrown as
 * [ComplianceRefusal] and rendered once by `TransactionsRefusalAdvice`.
 */
@Component
class SubmissionListingHandler(
    private val listing: ListSubmissions,
) {
    fun list(
        caller: EndUserPrincipal,
        query: ListingQuery,
    ): SubmissionPage {
        val tenant = caller.tenant ?: throw ComplianceRefusal(ClosedErrorCode.UNAUTHENTICATED)
        if (caller.entitySetCurrent && !caller.covers(EntityRef(query.entityRef))) {
            throw ComplianceRefusal(ClosedErrorCode.NOT_FOUND)
        }
        val request =
            ListSubmissionsRequest(
                tenant = tenant,
                principal = Principal(caller.actor, PrincipalKind.HUMAN),
                entityRef = query.entityRef,
                periodRef = query.periodRef,
                filter = filterOf(query),
                sort = query.sort,
                cursor = query.cursor,
                limit = query.limit,
                maxLimit = MAX_LIMIT,
            )
        return when (
            val result = listing.handle(request, CurrentDeadline.current() ?: error("no call deadline is bound"))
        ) {
            is ListSubmissionsResult.Page -> SubmissionPage(result.rows.map(::summary), result.nextCursor)
            ListSubmissionsResult.Forbidden -> throw ComplianceRefusal(ClosedErrorCode.ACCESS_DENIED)
            is ListSubmissionsResult.Refused -> throw ComplianceRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
        }
    }

    /** A filter value outside the contract enums or breaking a filter rule is SRT-001 before any read. */
    private fun filterOf(query: ListingQuery): SubmissionFilter =
        try {
            SubmissionFilter(
                direction = query.direction?.let { Direction.valueOf(it) },
                submissionType = query.submissionType?.let { SubmissionKind.valueOf(it) },
                state = query.state?.let { LifecycleState.valueOf(it) },
                categoryCode = query.categoryCode,
                counterparty = query.counterparty,
                amountMin = query.amountMin,
                amountMax = query.amountMax,
                acknowledged = query.acknowledged,
                bucket = query.bucket?.let { ReconciliationBucket.valueOf(it.name) },
            )
        } catch (_: IllegalArgumentException) {
            throw ComplianceRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
        }

    private fun summary(row: ListSubmissionsRow): SubmissionSummary {
        val facts = row.facts
        val taxable = facts.taxableAmount
        val gst = facts.gstAmount
        return SubmissionSummary(
            docUuid = java.util.UUID.fromString(row.docUuid),
            version = row.version,
            submissionType = SubmissionSummary.SubmissionType.valueOf(row.submissionType.name),
            state = contractState(row.state),
            dueDate = row.dueDate,
            totalAmount = if (taxable != null && gst != null) taxable.add(gst) else null,
            documentNumber = facts.documentNumber,
            documentTypeCode = facts.documentTypeCode,
            direction = SubmissionSummary.Direction.valueOf(facts.direction.name),
            counterpartyName = facts.counterpartyName,
            counterpartyUen = facts.counterpartyUen,
            transactionDate = row.transactionDate,
            taxableAmount = taxable,
            gstAmount = gst,
            submittedAt = row.submittedAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
            acknowledgedAt = row.acknowledgedAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
            acknowledgementId = row.acknowledgementId,
        )
    }

    /** A pre-submission state the contract does not list cannot be a submitted version: SRT-010, no detail. */
    private fun contractState(state: LifecycleState): SubmissionSummary.State =
        SubmissionSummary.State.entries.firstOrNull { it.name == state.name }
            ?: throw ComplianceRefusal(ClosedErrorCode.INTERNAL_ERROR)

    private companion object {
        /** The `maximum` of the parameter `limit` of `listSubmissions` in the contract. */
        const val MAX_LIMIT = 500
    }
}
