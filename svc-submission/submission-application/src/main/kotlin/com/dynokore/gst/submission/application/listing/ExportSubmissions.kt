package com.dynokore.gst.submission.application.listing

import com.dynokore.gst.submission.application.gstn.EntityGstns
import com.dynokore.gst.submission.domain.listing.ExportHeader
import com.dynokore.gst.submission.domain.listing.SubmissionExport
import com.dynokore.gst.submission.domain.listing.SubmissionFilter
import com.dynokore.gst.submission.domain.listing.SubmissionRows
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.math.BigDecimal
import java.time.Clock

/** One `exportSubmissions` call for the CSV format. */
data class ExportSubmissionsRequest(
    val tenant: TenantId,
    val principal: Principal,
    val entityRef: String,
    val periodRef: String,
    val filter: SubmissionFilter,
)

sealed interface ExportSubmissionsResult {
    /** [csv] is the file; the three values are the `X-Export-*` response headers, computed from the same rows. */
    data class Csv(
        val csv: String,
        val rowCount: Int,
        val totalTaxableAmount: BigDecimal,
        val totalGstAmount: BigDecimal,
    ) : ExportSubmissionsResult

    data object Forbidden : ExportSubmissionsResult

    data object BucketNotServed : ExportSubmissionsResult

    /** The entity has no stored GSTN (null or blank): the header cannot be built, nothing is read. */
    data object GstnUnavailable : ExportSubmissionsResult
}

/**
 * The CSV export of the submission list (INV-REQ-978, INV-AC-16330). An export carries content facts, so both
 * `submission.view-status` and `doc.view.content` are decided per request through [AuthorityReplica.decide]; a caller
 * without either is refused and neither the source nor the GSTN lookup is called (fail closed); an entity with no
 * stored GSTN gets [ExportSubmissionsResult.GstnUnavailable] and nothing is read. The rows come once from the
 * [SubmissionRowSource], the
 * one [SubmissionFilter.select] applies the filter, and the table, the CSV and the three header values all come from
 * those rows, so they tie back to the summary. generated-by is the acting user ID of the [Principal], never a role
 * (INV-Q-10647, INV-Q-10027).
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
    ): ExportSubmissionsResult =
        when {
            !decide(request, ListingActions.VIEW_STATUS, deadline) -> {
                ExportSubmissionsResult.Forbidden
            }

            !decide(request, ListingActions.VIEW_CONTENT, deadline) -> {
                ExportSubmissionsResult.Forbidden
            }

            request.filter.bucket != null && request.filter.bucket !in SERVED_BUCKETS -> {
                ExportSubmissionsResult.BucketNotServed
            }

            else -> {
                gstns
                    .gstnOf(request.tenant, request.entityRef)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { export(request, it) }
                    ?: ExportSubmissionsResult.GstnUnavailable
            }
        }

    private fun export(
        request: ExportSubmissionsRequest,
        gstn: String,
    ): ExportSubmissionsResult {
        val rows = source.rows(request.tenant, request.entityRef, request.periodRef).rows
        val selected =
            SubmissionRows.assemble(
                request.filter.select(rows, documentBuckets(rows)::get),
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
        val table = SubmissionExport.table(header, selected)
        return ExportSubmissionsResult.Csv(
            csv = SubmissionExport.csv(table),
            rowCount = table.rowCount,
            totalTaxableAmount = table.totalTaxableAmount,
            totalGstAmount = table.totalGstAmount,
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
