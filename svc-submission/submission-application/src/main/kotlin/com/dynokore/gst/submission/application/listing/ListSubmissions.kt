package com.dynokore.gst.submission.application.listing

import com.dynokore.gst.submission.domain.listing.CursorDecoding
import com.dynokore.gst.submission.domain.listing.CursorPosition
import com.dynokore.gst.submission.domain.listing.ListCursor
import com.dynokore.gst.submission.domain.listing.SortField
import com.dynokore.gst.submission.domain.listing.SortKey
import com.dynokore.gst.submission.domain.listing.SubmissionFilter
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.gst.submission.domain.listing.SubmissionRow
import com.dynokore.gst.submission.domain.listing.SubmissionRows
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.reconciliation.DocFact
import com.dynokore.gst.submission.domain.reconciliation.ReconciliationBucket
import com.dynokore.gst.submission.domain.reconciliation.SentVersusAcknowledged
import com.dynokore.gst.submission.domain.reconciliation.VersionFact
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.gst.submission.domain.register.ListFactsPolicy
import com.dynokore.gst.submission.domain.register.MaskedListFacts
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.time.LocalDate

/** The two scoped permission keys the listing use case decides (INV-REQ-975, INV-Q-10027): no role name is read. */
object ListingActions {
    const val VIEW_STATUS = "submission.view-status"
    const val VIEW_CONTENT = "doc.view.content"
}

/** [maxLimit] is supplied by the caller (the HTTP slice passes the contract maximum), never a literal here. */
data class ListSubmissionsRequest(
    val tenant: TenantId,
    val principal: Principal,
    val entityRef: String,
    val periodRef: String,
    val filter: SubmissionFilter,
    val sort: String?,
    val cursor: String?,
    val limit: Int,
    val maxLimit: Int,
)

/** One list row as served to the caller: the content facts already masked per [ListFactsPolicy.mask]. */
data class ListSubmissionsRow(
    val docUuid: String,
    val version: Int,
    val submissionType: SubmissionKind,
    val state: LifecycleState,
    val dueDate: LocalDate,
    val transactionDate: LocalDate,
    val facts: MaskedListFacts,
    val submittedAt: Instant?,
    val acknowledgedAt: Instant?,
    val acknowledgementId: String?,
)

/** Why [ListSubmissions.handle] refused the request; never carries a value of the request itself. */
enum class ListSubmissionsRefusal { MASKED_FACT_FILTER, CURSOR_MISMATCH, BAD_SORT, LIMIT_OUT_OF_RANGE }

sealed interface ListSubmissionsResult {
    data class Page(
        val rows: List<ListSubmissionsRow>,
        val nextCursor: String?,
    ) : ListSubmissionsResult

    data object Forbidden : ListSubmissionsResult

    data class Refused(
        val reason: ListSubmissionsRefusal,
    ) : ListSubmissionsResult
}

/**
 * The submission list use case (INV-REQ-975, INV-AC-21818): decides `submission.view-status` and `doc.view.content`
 * per request, masks content facts the caller may not view, refuses a filter on a masked fact, validates the sort
 * and the page limit, reads the register rows once, then filters, sorts, pages with a cursor bound to the filter and
 * sort, and logs the left-out row count with no document, tenant or party value. The decision is
 * [AuthorityReplica.decide] with the acting user id carried in the [Principal]; no role name is read (INV-Q-10027).
 */
class ListSubmissions(
    private val replica: AuthorityReplica,
    private val source: SubmissionRowSource,
) {
    fun handle(
        request: ListSubmissionsRequest,
        deadline: Deadline,
    ): ListSubmissionsResult {
        if (!decide(request, ListingActions.VIEW_STATUS, deadline).allowed) {
            return ListSubmissionsResult.Forbidden
        }
        val mayViewContent = decide(request, ListingActions.VIEW_CONTENT, deadline).allowed
        return validateAndPage(request, mayViewContent)
    }

    private fun validateAndPage(
        request: ListSubmissionsRequest,
        mayViewContent: Boolean,
    ): ListSubmissionsResult {
        val sort = parseSort(request.sort)
        return when {
            !mayViewContent && touchesMaskedFact(request.filter) -> {
                ListSubmissionsResult.Refused(ListSubmissionsRefusal.MASKED_FACT_FILTER)
            }

            sort == null -> {
                ListSubmissionsResult.Refused(ListSubmissionsRefusal.BAD_SORT)
            }

            request.limit < 1 || request.limit > request.maxLimit -> {
                ListSubmissionsResult.Refused(ListSubmissionsRefusal.LIMIT_OUT_OF_RANGE)
            }

            else -> {
                buildPage(request, sort, mayViewContent)
            }
        }
    }

    private fun buildPage(
        request: ListSubmissionsRequest,
        sort: SortKey,
        mayViewContent: Boolean,
    ): ListSubmissionsResult {
        val read = source.rows(request.tenant, request.entityRef, request.periodRef)
        val ordered = SubmissionRows.assemble(request.filter.select(read.rows, ::bucketOf), sort)
        val filterKey = request.filter.canonical()
        val sortKey = sortText(sort)
        val from = request.cursor?.let { resumeIndex(it, filterKey, sortKey, ordered) }
        return if (request.cursor != null && from == null) {
            ListSubmissionsResult.Refused(ListSubmissionsRefusal.CURSOR_MISMATCH)
        } else {
            logWithoutFacts(read.withoutFacts)
            val slice = ordered.drop(from ?: 0)
            val pageRows = slice.take(request.limit)
            val nextCursor =
                if (slice.size > pageRows.size) {
                    val last = pageRows.last()
                    val lastValue =
                        if (sort.field == SortField.DUE_DATE) {
                            last.dueDate.toString()
                        } else {
                            last.transactionDate.toString()
                        }
                    val position = CursorPosition(lastValue, last.docUuid, last.version)
                    ListCursor.encode(position, filterKey, sortKey)
                } else {
                    null
                }
            ListSubmissionsResult.Page(pageRows.map { it.toResult(mayViewContent) }, nextCursor)
        }
    }

    /** The index right after the cursor's position in [ordered]; null when the cursor itself is refused. */
    private fun resumeIndex(
        token: String,
        filterKey: String,
        sortKey: String,
        ordered: List<SubmissionRow>,
    ): Int? =
        when (val decoded = ListCursor.decode(token, filterKey, sortKey)) {
            is CursorDecoding.Resumed -> {
                val position = decoded.position
                val at = ordered.indexOfFirst { it.docUuid == position.docUuid && it.version == position.version }
                if (at < 0) 0 else at + 1
            }

            is CursorDecoding.Refused -> {
                null
            }
        }

    private fun parseSort(sort: String?): SortKey? =
        if (sort == null) SubmissionRows.DEFAULT_SORT else runCatching { SortKey.parse(sort) }.getOrNull()

    private fun decide(
        request: ListSubmissionsRequest,
        action: String,
        deadline: Deadline,
    ) = replica.decide(AuthorizationRequest(request.tenant, request.principal, request.entityRef, action), deadline)

    private fun touchesMaskedFact(filter: SubmissionFilter): Boolean =
        filter.counterparty != null || filter.amountMin != null ||
            filter.amountMax != null || filter.categoryCode != null

    /**
     * The row's own reconciliation class (INV-REQ-976), without the overdue threshold: this request-scoped use case
     * threads no clock (ARC-13), and the overdue determination is the reconciliation view's own concern, out of this
     * task's scope. A row in SENT or SENT_UNCONFIRMED state therefore never matches a bucket filter here.
     */
    private fun bucketOf(row: SubmissionRow): ReconciliationBucket? {
        val version = VersionFact(row.version, row.state, acknowledgementDeadline = null)
        val doc = DocFact(row.docUuid, listOf(version), row.taxableAmount)
        return SentVersusAcknowledged.bucketOf(doc, Instant.EPOCH)
    }

    private fun sortText(sort: SortKey): String {
        val name = if (sort.field == SortField.DUE_DATE) "dueDate" else "transactionDate"
        return if (sort.descending) "-$name" else name
    }

    private fun logWithoutFacts(count: Int) {
        if (count > 0) {
            LOG.log(System.Logger.Level.WARNING, "list read left out $count row(s) without usable facts")
        }
    }

    private fun SubmissionRow.toResult(mayViewContent: Boolean): ListSubmissionsRow {
        val facts =
            ListFacts(
                documentNumber = documentNumber,
                documentTypeCode = documentTypeCode,
                direction = direction,
                counterpartyName = counterpartyName,
                counterpartyUen = counterpartyUen,
                taxableAmount = taxableAmount,
                gstAmount = gstAmount,
                categoryCodes = categoryCodes,
            )
        return ListSubmissionsRow(
            docUuid = docUuid,
            version = version,
            submissionType = submissionType,
            state = state,
            dueDate = dueDate,
            transactionDate = transactionDate,
            facts = ListFactsPolicy.mask(facts, mayViewContent),
            submittedAt = submittedAt,
            acknowledgedAt = acknowledgedAt,
            acknowledgementId = acknowledgementId,
        )
    }

    private companion object {
        val LOG: System.Logger = System.getLogger(ListSubmissions::class.java.name)
    }
}
