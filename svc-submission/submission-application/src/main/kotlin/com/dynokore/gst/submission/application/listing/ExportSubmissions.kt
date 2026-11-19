package com.dynokore.gst.submission.application.listing

import com.dynokore.gst.submission.application.gstn.EntityGstns
import com.dynokore.gst.submission.domain.listing.ExportHeader
import com.dynokore.gst.submission.domain.listing.ExportTable
import com.dynokore.gst.submission.domain.listing.SubmissionExport
import com.dynokore.gst.submission.domain.listing.SubmissionFilter
import com.dynokore.gst.submission.domain.listing.SubmissionRows
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant

/** One `exportSubmissions` call for the CSV format. */
data class ExportSubmissionsRequest(
    val tenant: TenantId,
    val principal: Principal,
    val entityRef: String,
    val periodRef: String,
    val filter: SubmissionFilter,
    val now: Instant,
)

sealed interface ExportSubmissionsResult {
    /**
     * [csv] is the file; the three values are the `X-Export-*` response headers; [table] is the format-neutral table
     * from which all four are taken, so another format (XLSX) is written from the same rows, header and masking.
     */
    data class Csv(
        val csv: String,
        val rowCount: Int,
        val totalTaxableAmount: BigDecimal,
        val totalGstAmount: BigDecimal,
        val table: ExportTable,
    ) : ExportSubmissionsResult

    data object Forbidden : ExportSubmissionsResult

    data object BucketNotServed : ExportSubmissionsResult

    /** The filter touches a masked fact and the caller lacks `doc.view.content`: nothing is read. */
    data object MaskedFactFilter : ExportSubmissionsResult

    /** The entity has no stored GSTN (null or blank): the header cannot be built, nothing is read. */
    data object GstnUnavailable : ExportSubmissionsResult
}

/**
 * The CSV export of the submission list (INV-REQ-978, INV-AC-23395). The status key is decided per request through
 * [AuthorityReplica.decide]: `machine.submissions.read` ([ListingActions.VIEW_STATUS_MACHINE]) for a machine principal,
 * `submission.view-status` ([ListingActions.VIEW_STATUS]) for every other one; a caller without it is refused and
 * nothing is read (fail closed). `doc.view.content` is decided for every principal and is not a refusal: without it
 * the content facts are masked as the list masks them (`ListFactsPolicy.mask`), the row count and totals still come
 * from all selected rows, and a filter on a masked fact is [ExportSubmissionsResult.MaskedFactFilter]. An entity with
 * no stored GSTN gets [ExportSubmissionsResult.GstnUnavailable] and nothing is read. The rows come once from the
 * [SubmissionRowSource], the one [SubmissionFilter.select] applies the filter, and the table, the CSV and the three
 * header values all come from those rows, so they tie back to the summary. generated-by is the acting user ID of the
 * [Principal], never a role (INV-Q-10647, INV-Q-10027).
 */
class ExportSubmissions(
    private val replica: AuthorityReplica,
    private val source: SubmissionRowSource,
    private val gstns: EntityGstns,
    private val clock: Clock,
) {
    fun handle(
        request: ExportSubmissionsRequest,
        deadline: Deadline,
    ): ExportSubmissionsResult {
        val statusKey =
            if (request.principal.kind == PrincipalKind.MACHINE) {
                ListingActions.VIEW_STATUS_MACHINE
            } else {
                ListingActions.VIEW_STATUS
            }
        if (!decide(request, statusKey, deadline)) {
            return ExportSubmissionsResult.Forbidden
        }
        val mayViewContent = decide(request, ListingActions.VIEW_CONTENT, deadline)
        return when {
            request.filter.bucket != null && request.filter.bucket !in SERVED_BUCKETS -> {
                ExportSubmissionsResult.BucketNotServed
            }

            !mayViewContent && touchesMaskedFact(request.filter) -> {
                ExportSubmissionsResult.MaskedFactFilter
            }

            else -> {
                gstns
                    .gstnOf(request.tenant, request.entityRef)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { export(request, it, mayViewContent) }
                    ?: ExportSubmissionsResult.GstnUnavailable
            }
        }
    }

    private fun export(
        request: ExportSubmissionsRequest,
        gstn: String,
        mayViewContent: Boolean,
    ): ExportSubmissionsResult {
        val rows = source.rows(request.tenant, request.entityRef, request.periodRef).rows
        val selected =
            SubmissionRows.assemble(
                request.filter.select(rows, documentBuckets(rows, request.now)::get),
                SubmissionRows.DEFAULT_SORT,
            )
        val header =
            ExportHeader(
                entityRef = request.entityRef,
                gstn = gstn,
                periodRef = request.periodRef,
                generatedAt = clock.instant(),
                generatedBy = request.principal.id,
                filter = request.filter.canonical(),
            )
        val table = SubmissionExport.table(header, selected, mayViewContent)
        return ExportSubmissionsResult.Csv(
            csv = SubmissionExport.csv(table),
            rowCount = table.rowCount,
            totalTaxableAmount = table.totalTaxableAmount,
            totalGstAmount = table.totalGstAmount,
            table = table,
        )
    }

    private fun decide(
        request: ExportSubmissionsRequest,
        action: String,
        deadline: Deadline,
    ): Boolean =
        replica
            .decide(AuthorizationRequest(request.tenant, request.principal, request.entityRef, action), deadline)
            .allowed
}
