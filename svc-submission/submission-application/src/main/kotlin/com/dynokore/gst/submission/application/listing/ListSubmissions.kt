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
import com.dynokore.gst.submission.domain.reconciliation.ReconciliationBucket
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.gst.submission.domain.register.ListFactsPolicy
import com.dynokore.gst.submission.domain.register.MaskedListFacts
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.time.LocalDate

/**
 * The three scoped permission keys the listing use case decides (INV-REQ-975, INV-Q-10027): no role name is read.
 * [VIEW_STATUS] is asked for every principal that is not a MACHINE, [VIEW_STATUS_MACHINE] (the `x-operation.permission`
 * of `listSubmissions`) for a MACHINE principal, and [VIEW_CONTENT] for every principal.
 */
object ListingActions {
    const val VIEW_STATUS = "submission.view-status"
    const val VIEW_STATUS_MACHINE = "machine.submissions.read"
    const val VIEW_CONTENT = "doc.view.content"
    const val VIEW_SUMMARY_MACHINE = "machine.status.read"
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

/**
 * The reconciliation classes this list can serve. SENT_NOT_ACKNOWLEDGED needs an acknowledgement deadline and a
 * clock that the register rows and this request-scoped use case do not carry (open design question on the
 * reconciliation task), so that drill-down is refused rather than silently matching nothing.
 */
val SERVED_BUCKETS: Set<ReconciliationBucket> =
    setOf(
        ReconciliationBucket.SENT_ACKNOWLEDGED,
        ReconciliationBucket.REJECTED,
        ReconciliationBucket.DEAD_LETTERED,
        ReconciliationBucket.NEVER_SENT,
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
enum class ListSubmissionsRefusal {
    MASKED_FACT_FILTER,
    CURSOR_MISMATCH,
    BAD_SORT,
    LIMIT_OUT_OF_RANGE,
    BUCKET_NOT_SERVED,
}

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
        val viewKey =
            if (request.principal.kind == PrincipalKind.MACHINE) {
                ListingActions.VIEW_STATUS_MACHINE
            } else {
                ListingActions.VIEW_STATUS
            }
        if (!decide(request, viewKey, deadline).allowed) {
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

            request.filter.bucket != null && request.filter.bucket !in SERVED_BUCKETS -> {
                ListSubmissionsResult.Refused(ListSubmissionsRefusal.BUCKET_NOT_SERVED)
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
        val ordered = SubmissionRows.assemble(request.filter.select(read.rows, documentBuckets(read.rows)::get), sort)
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
                val descending = sortKey.startsWith("-")
                val after =
                    ordered.indexOfFirst { row ->
                        val value =
                            if (sortKey.endsWith(
                                    "dueDate",
                                )
                            ) {
                                row.dueDate.toString()
                            } else {
                                row.transactionDate.toString()
                            }
                        val byValue = value.compareTo(position.sortValue).let { if (descending) -it else it }
                        val byKey =
                            if (byValue != 0) {
                                byValue
                            } else {
                                val byDoc = row.docUuid.compareTo(position.docUuid)
                                if (byDoc != 0) byDoc else row.version.compareTo(position.version)
                            }
                        byKey > 0
                    }
                if (after < 0) ordered.size else after
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
