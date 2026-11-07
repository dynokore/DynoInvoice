package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.application.gapreport.ExportGapReport
import com.dynokore.gst.masterdata.application.gapreport.ExportLine
import com.dynokore.gst.masterdata.application.gapreport.GapReportRecord
import com.dynokore.gst.masterdata.application.gapreport.ReadGapReport
import com.dynokore.gst.masterdata.application.gapreport.RecordState
import com.dynokore.gst.masterdata.application.gapreport.RunGapReport
import com.dynokore.gst.masterdata.application.permission.MasterdataPermissions
import com.dynokore.gst.masterdata.application.permission.TenantPermission
import com.dynokore.gst.masterdata.contract.api.GapReportApi
import com.dynokore.gst.masterdata.contract.model.ErrorEnvelope
import com.dynokore.gst.masterdata.contract.model.GapReport
import com.dynokore.gst.masterdata.contract.model.GapReportNotCheckedInner
import com.dynokore.gst.masterdata.contract.model.GapReportRowsInner
import com.dynokore.gst.masterdata.contract.model.GapReportStatus
import com.dynokore.gst.masterdata.contract.model.OperationState
import com.dynokore.gst.masterdata.contract.model.RunGapReportRequest
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.time.ZoneOffset
import java.util.UUID

/**
 * `runGapReport`, `getGapReport` and `exportGapReport` (INV-T-4944, INV-REQ-316, INV-REQ-832). The permission is the
 * first statement of each operation (INV-Q-17215 A): `gap-report.view` for the first two, `gap-report.export` for the
 * export. A request without `entityRef`, or with a blank one, is refused 400 SRT-001 before anything starts. A report
 * that outlasts `x-timeout` answers 202 with `Location` and `Retry-After` and the RUNNING status as its body (API-17).
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller
open class GapReportController(
    private val run: RunGapReport,
    private val read: ReadGapReport,
    private val export: ExportGapReport,
    private val permission: TenantPermission,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper,
) : GapReportApi {
    override fun runGapReport(
        idempotencyKey: UUID,
        runGapReportRequest: RunGapReportRequest?,
    ): ResponseEntity<GapReport> {
        val actor = allowed(MasterdataPermissions.GAP_REPORT_VIEW)
        val entity =
            runGapReportRequest?.entityRef?.trim()?.takeIf { it.isNotEmpty() }
                ?: CounterpartyChangeMapping.invalid("entityRef is required")
        val answer = run.handle(entity, actor)
        val status = status(answer.record)
        if (answer.finished) return ResponseEntity.ok(status.result)
        throw LongCommandAccepted(
            status,
            "/internal/v1/masterdata/gap-reports/${answer.record.gapReportId}",
            RETRY_AFTER_SECONDS,
        )
    }

    override fun getGapReport(gapReportId: String): ResponseEntity<GapReportStatus> {
        val actor = allowed(MasterdataPermissions.GAP_REPORT_VIEW)
        val record = read.handle(gapReportId, actor) ?: throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
        return ResponseEntity.ok(status(record))
    }

    override fun exportGapReport(gapReportId: String): ResponseEntity<Resource> {
        val actor = allowed(MasterdataPermissions.GAP_REPORT_EXPORT)
        val lines = export.handle(gapReportId, actor) ?: throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
        val text = (listOf(HEADER) + lines.map(::csv)).joinToString("\r\n", postfix = "\r\n")
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(ByteArrayResource(text.toByteArray(Charsets.UTF_8)))
    }

    private fun allowed(key: String): String {
        val actor = tenants.currentActor().orEmpty()
        if (!permission.holds(actor, key)) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        return actor
    }

    private fun status(record: GapReportRecord): GapReportStatus =
        GapReportStatus(
            gapReportId = record.gapReportId,
            state = OperationState.valueOf(record.state.name),
            startedAt = record.startedAt.atOffset(ZoneOffset.UTC),
            completedAt = record.completedAt?.atOffset(ZoneOffset.UTC),
            result =
                record.result?.takeIf { record.state == RecordState.SUCCEEDED }?.let { result ->
                    GapReport(
                        rows =
                            result.rows.map {
                                GapReportRowsInner(
                                    it.counterpartyRef,
                                    GapReportRowsInner.FindingClass.valueOf(it.findingClass.name),
                                    it.errorCode,
                                )
                            },
                        notChecked =
                            result.notChecked.map {
                                GapReportNotCheckedInner(it, GapReportNotCheckedInner.Reason.REGISTER_UNAVAILABLE)
                            },
                    )
                },
            error = record.error?.let { mapper.readValue(it, ErrorEnvelope::class.java) },
        )

    private fun csv(line: ExportLine): String =
        listOf(
            line.counterpartyRef,
            line.findingClass?.name,
            line.errorCode,
            line.uen,
            line.legalName,
            line.addressLine1,
            line.postalCode,
        ).joinToString(",") { cell(it) }

    /** RFC 4180: a cell with a comma, quote or line break is quoted and its quotes doubled. */
    private fun cell(value: String?): String {
        val text = value.orEmpty()
        return if (text.any { it in SPECIAL }) {
            "\"" + text.replace("\"", "\"\"") + "\""
        } else {
            text
        }
    }

    private companion object {
        val SPECIAL = setOf(',', '"', '\r', '\n')
        const val HEADER = "counterpartyRef,findingClass,errorCode,uen,legalName,addressLine1,postalCode"
        const val RETRY_AFTER_SECONDS = 2L
    }
}
