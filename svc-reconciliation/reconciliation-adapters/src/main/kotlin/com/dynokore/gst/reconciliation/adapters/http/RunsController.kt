package com.dynokore.gst.reconciliation.adapters.http

import com.dynokore.gst.reconciliation.application.run.GetRun
import com.dynokore.gst.reconciliation.application.run.ListRuns
import com.dynokore.gst.reconciliation.application.run.RecordedRun
import com.dynokore.gst.reconciliation.application.workpaper.GetWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.WorkPaperView
import com.dynokore.gst.reconciliation.contract.api.RunsApi
import com.dynokore.gst.reconciliation.contract.model.Attestation
import com.dynokore.gst.reconciliation.contract.model.BoxComparison
import com.dynokore.gst.reconciliation.contract.model.ErrorEnvelope
import com.dynokore.gst.reconciliation.contract.model.Run
import com.dynokore.gst.reconciliation.contract.model.RunPage
import com.dynokore.gst.reconciliation.contract.model.UnmappedLine
import com.dynokore.gst.reconciliation.contract.model.VarianceClass
import com.dynokore.gst.reconciliation.contract.model.VarianceShare
import com.dynokore.gst.reconciliation.contract.model.WorkPaper
import com.dynokore.gst.reconciliation.contract.model.WorkPaperHeader
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.net.URI
import java.time.ZoneOffset
import java.util.UUID

/**
 * The run, work-paper and attestation operations of the reconciliation contract (TPL-08). `listRuns` and `getRun`
 * are implemented (INV-T-24376, INV-REQ-656), and `getWorkPaper` (INV-T-24380, INV-REQ-633); every other
 * operation answers 501 until the task named for it in
 * `operations.tsv` overrides its method: the generated interface's default is NOT_IMPLEMENTED.
 *
 * Who may call is decided by the generated check of the operation, not here. Each read runs in one tenant
 * transaction. A refusal is a client error (400 or 404), never a 5xx; a limit is never clamped.
 */
@RestController
open class RunsController(
    private val listRuns: ListRuns,
    private val getRun: GetRun,
    private val getWorkPaper: GetWorkPaper,
    private val transactions: TransactionRunner,
    private val tenants: TenantContext,
) : RunsApi {
    override fun listRuns(
        entityRef: String,
        periodRef: String,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<RunPage> {
        val tenant = tenants.current().toString()
        val page =
            try {
                transactions.inTransaction {
                    val view = listRuns.execute(tenant, entityRef, periodRef, limit, cursor)
                    RunPage(items = view.items.map(::run), nextCursor = view.nextCursor)
                }
            } catch (e: IllegalArgumentException) {
                return refusal(ClosedErrorCode.CONTRACT_VIOLATION, e.message ?: "invalid request")
            }
        return ResponseEntity.ok(page)
    }

    override fun getRun(runId: UUID): ResponseEntity<Run> {
        val tenant = tenants.current().toString()
        val snapshot =
            transactions.inTransaction { getRun.execute(tenant, runId.toString()) }
                ?: return refusal(ClosedErrorCode.NOT_FOUND, "No such run.")
        return ResponseEntity.ok(run(snapshot.run))
    }

    override fun getWorkPaper(runId: UUID): ResponseEntity<WorkPaper> {
        val tenant = tenants.current().toString()
        val view =
            transactions.inTransaction { getWorkPaper.execute(tenant, runId.toString()) }
                ?: return refusal(ClosedErrorCode.NOT_FOUND, "No such run.")
        return ResponseEntity.ok(workPaper(view))
    }

    private fun workPaper(view: WorkPaperView) =
        WorkPaper(
            runId = UUID.fromString(view.runId),
            header =
                WorkPaperHeader(
                    entityRef = view.header.entityRef,
                    periodRef = view.header.periodRef,
                    preparedAt = view.header.preparedAt.atOffset(ZoneOffset.UTC),
                    returnVersion = view.header.returnVersion,
                    returnForm = WorkPaperHeader.ReturnForm.forValue(view.header.returnForm),
                ),
            boxes =
                view.boxes.map { b ->
                    BoxComparison(
                        box = b.box,
                        submittedAmount = BigDecimal(b.submittedAmount),
                        filedAmount = BigDecimal(b.filedAmount),
                        variance = BigDecimal(b.variance),
                        varianceClasses =
                            b.varianceClasses.map {
                                VarianceShare(VarianceClass.forValue(it.varianceClass), BigDecimal(it.amount))
                            },
                    )
                },
            unexplainedResidual = BigDecimal(view.unexplainedResidual),
            supportingDocuments = view.supportingDocuments,
            attestation =
                view.attestation?.let {
                    Attestation(attestedBy = it.attestedBy, attestedAt = it.attestedAt.atOffset(ZoneOffset.UTC))
                },
            unmappedLines =
                view.unmappedLines.map {
                    UnmappedLine(UUID.fromString(it.docUuid), it.version, it.lineNumber, it.taxCode)
                },
        )

    private fun run(recorded: RecordedRun) =
        Run(
            runId = UUID.fromString(recorded.runId),
            entityRef = recorded.entityRef,
            periodRef = recorded.periodRef,
            completedAt = recorded.completedAt.atOffset(ZoneOffset.UTC),
            returnVersion = recorded.returnVersion,
            returnForm = Run.ReturnForm.forValue(recorded.returnForm),
        )

    /** The body of an error response is the API-20 envelope; the generated signature names the success type only. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> refusal(
        closed: ClosedErrorCode,
        detail: String,
    ): ResponseEntity<T> {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
        val envelope =
            ErrorEnvelope(
                type = URI.create(Problems.typeOf(closed.code)),
                title = closed.title,
                status = closed.httpStatus,
                code = closed.code,
                correlationId = correlation,
                detail = detail,
                severity = ErrorEnvelope.Severity.BLOCK,
                disposition = closed.disposition,
                remediationOwner = closed.remediationOwner,
            )
        return ResponseEntity
            .status(envelope.status)
            .header(HttpHeaders.CONTENT_TYPE, Problems.CONTENT_TYPE)
            .body(envelope) as ResponseEntity<T>
    }
}
