package com.dynokore.gst.submission.adapters.http

import com.dynokore.gst.submission.adapters.export.XlsxExportWriter
import com.dynokore.gst.submission.application.listing.ExportSubmissions
import com.dynokore.gst.submission.application.listing.ExportSubmissionsRequest
import com.dynokore.gst.submission.application.listing.ExportSubmissionsResult
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.listing.SubmissionFilter
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.reconciliation.ReconciliationBucket
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import com.dynokore.gst.submission.contract.model.ReconciliationBucket as ContractBucket

/** The filters of one `exportSubmissions` call, as the contract names them. */
data class ExportQuery(
    val entityRef: String,
    val periodRef: String,
    val format: String,
    val direction: String?,
    val submissionType: String?,
    val state: String?,
    val categoryCode: String?,
    val counterparty: String?,
    val amountMin: BigDecimal?,
    val amountMax: BigDecimal?,
    val acknowledged: Boolean?,
    val bucket: ContractBucket?,
)

/**
 * The delegate that serves `exportSubmissions` for `TransactionsController` (the operation is tagged `transactions`,
 * so its generated interface is already implemented there; INV-REQ-978). CSV and XLSX are served from the same
 * `ExportTable` of the use case result; PDF and any other value are SRT-001 before the use case is called. The
 * permission is
 * decided inside [ExportSubmissions] through `AuthorityReplica.decide`; this class reads no role name and holds no
 * permission string (INV-Q-10027). Refusals are thrown as [ComplianceRefusal] and rendered once by
 * `TransactionsRefusalAdvice`.
 */
@Component
class SubmissionExportController(
    private val export: ExportSubmissions,
) {
    fun export(
        caller: Principal,
        query: ExportQuery,
        now: java.time.Instant,
    ): ResponseEntity<Resource> {
        val tenant = caller.tenant ?: throw ComplianceRefusal(ClosedErrorCode.UNAUTHENTICATED)
        requireServed(query.format)
        val request =
            ExportSubmissionsRequest(tenant, caller, query.entityRef, query.periodRef, filterOf(query), now)
        val deadline = CurrentDeadline.current() ?: error("no call deadline is bound")
        val csv = export.handle(request, deadline).let { it as? ExportSubmissionsResult.Csv ?: throw refusalOf(it) }
        val headers = HttpHeaders()
        headers.set(ROW_COUNT, csv.rowCount.toString())
        headers.set(TOTAL_TAXABLE, csv.totalTaxableAmount.toPlainString())
        headers.set(TOTAL_GST, csv.totalGstAmount.toPlainString())
        val body =
            if (query.format == XLSX) {
                headers.contentType = MediaType.parseMediaType(XLSX_TYPE)
                headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"submissions.xlsx\"")
                ByteArrayOutputStream().also { XlsxExportWriter().write(csv.table, it) }.toByteArray()
            } else {
                headers.contentType = MediaType.parseMediaType("text/csv")
                csv.csv.toByteArray(Charsets.UTF_8)
            }
        return ResponseEntity.ok().headers(headers).body(ByteArrayResource(body))
    }

    /** Only CSV and XLSX are served; any other value is SRT-001 before the use case is called. */
    private fun requireServed(format: String) {
        if (format != CSV && format != XLSX) throw ComplianceRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
    }

    /** The refusal a result that is not the CSV stands for. */
    private fun refusalOf(result: ExportSubmissionsResult): ComplianceRefusal =
        when (result) {
            is ExportSubmissionsResult.Csv -> error("a CSV result is not a refusal")
            ExportSubmissionsResult.Forbidden -> ComplianceRefusal(ClosedErrorCode.ACCESS_DENIED)
            ExportSubmissionsResult.BucketNotServed -> ComplianceRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
            ExportSubmissionsResult.MaskedFactFilter -> ComplianceRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
            ExportSubmissionsResult.GstnUnavailable -> ComplianceRefusal(ClosedErrorCode.SERVICE_NOT_READY)
        }

    /** A filter value outside the contract enums or breaking a filter rule is SRT-001 before any read. */
    private fun filterOf(query: ExportQuery): SubmissionFilter =
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

    private companion object {
        const val CSV = "CSV"
        const val XLSX = "XLSX"
        const val XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val ROW_COUNT = "X-Export-Row-Count"
        const val TOTAL_TAXABLE = "X-Export-Total-Taxable-Amount"
        const val TOTAL_GST = "X-Export-Total-Gst-Amount"
    }
}
