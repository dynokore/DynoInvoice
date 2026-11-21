package com.dynokore.gst.reconciliation.adapters.http

import com.dynokore.gst.reconciliation.application.run.GetRun
import com.dynokore.gst.reconciliation.application.run.ListRuns
import com.dynokore.gst.reconciliation.application.run.RecordedRun
import com.dynokore.gst.reconciliation.application.workpaper.AttestWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.AttestationResult
import com.dynokore.gst.reconciliation.application.workpaper.GetWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.SealWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.StoredAttestation
import com.dynokore.gst.reconciliation.application.workpaper.WorkPaperView
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportWorkPaper
import com.dynokore.gst.reconciliation.contract.api.RunsApi
import com.dynokore.gst.reconciliation.contract.model.AttestWorkPaperRequest
import com.dynokore.gst.reconciliation.contract.model.Attestation
import com.dynokore.gst.reconciliation.contract.model.AttestationSoleAttester
import com.dynokore.gst.reconciliation.contract.model.BoxComparison
import com.dynokore.gst.reconciliation.contract.model.ErrorEnvelope
import com.dynokore.gst.reconciliation.contract.model.Run
import com.dynokore.gst.reconciliation.contract.model.RunPage
import com.dynokore.gst.reconciliation.contract.model.UnmappedLine
import com.dynokore.gst.reconciliation.contract.model.VarianceClass
import com.dynokore.gst.reconciliation.contract.model.VarianceShare
import com.dynokore.gst.reconciliation.contract.model.WorkPaper
import com.dynokore.gst.reconciliation.contract.model.WorkPaperHeader
import com.dynokore.platform.auditlog.AuditedOperations
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.net.URI
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.gst.reconciliation.application.workpaper.AttestationRefusal as UseCaseRefusal

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
@Suppress("LongParameterList", "TooManyFunctions")
open class RunsController(
    private val listRuns: ListRuns,
    private val getRun: GetRun,
    private val getWorkPaper: GetWorkPaper,
    private val transactions: TransactionRunner,
    private val tenants: TenantContext,
    private val attestWorkPaper: AttestWorkPaper,
    private val sealWorkPaper: SealWorkPaper,
    private val attestAuthority: AttestAuthority,
    private val requests: ObjectProvider<HttpServletRequest>,
    exportWorkPaper: ExportWorkPaper,
    exportAuthority: ExportAuthority,
    auditedOperations: AuditedOperations,
) : RunsApi {
    private val delivery =
        ExportDelivery(getRun, transactions, tenants, exportWorkPaper, exportAuthority, auditedOperations)

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

    /**
     * `attestWorkPaper` (INV-T-24459, INV-REQ-947): the run is read first (unknown run or an entity outside the
     * caller's current entity set is 404, no decision), then one scoped permission is decided, then the use case runs
     * with the caller's user ID as the approver, then the work-paper is sealed. A refusal throws
     * [AttestationRefusal], rendered once by [AttestationRefusalAdvice].
     */
    override fun attestWorkPaper(
        runId: UUID,
        attestWorkPaperRequest: AttestWorkPaperRequest,
    ): ResponseEntity<Attestation> {
        val caller = caller()
        val tenant = tenants.current().toString()
        val entityRef = entityOf(caller, tenant, runId)
        if (!attestAuthority.holds(caller.actor, ATTEST_PERMISSION, entityRef)) {
            throw AttestationRefusal(AttestationRefusalCode.ACCESS_DENIED)
        }
        val result =
            transactions.inTransaction {
                attestWorkPaper.execute(
                    tenant,
                    runId.toString(),
                    attestWorkPaperRequest.soleAttesterAcknowledgement,
                    caller.actor,
                )
            }
        val stored = storedOrRefusal(result)
        sealWorkPaper.execute(tenant, runId.toString())
        return ResponseEntity.ok(attestation(stored))
    }

    /**
     * `exportWorkPaper` (INV-T-24486, INV-REQ-982): no token is 401, then [ExportDelivery] runs the one fail-closed
     * order (the run's entity, the scoped permission, the use case for either format, the audit entry).
     */
    override fun exportWorkPaper(
        runId: UUID,
        format: String,
    ): ResponseEntity<Resource> {
        val caller =
            requests.getObject().getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal
                ?: return exportRefusal(ExportRefusal.UNAUTHENTICATED)
        return when (val step = delivery.deliver(caller, runId, format)) {
            is ExportStep.Answer -> step.response
            ExportStep.UnknownFormat -> refusal(ClosedErrorCode.CONTRACT_VIOLATION, "Unknown export format.")
        }
    }

    private fun caller(): EndUserPrincipal =
        requests.getObject().getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal
            ?: throw AttestationRefusal(AttestationRefusalCode.UNAUTHENTICATED)

    /** The run's entity, read before any decision; unknown run or entity outside a current set: 404 (API-24). */
    private fun entityOf(
        caller: EndUserPrincipal,
        tenant: String,
        runId: UUID,
    ): String {
        val entityRef = transactions.inTransaction { getRun.execute(tenant, runId.toString()) }?.run?.entityRef
        val visible = entityRef != null && (!caller.entitySetCurrent || caller.covers(EntityRef(entityRef)))
        return entityRef.takeIf { visible } ?: throw AttestationRefusal(AttestationRefusalCode.NOT_FOUND)
    }

    private fun storedOrRefusal(result: AttestationResult): StoredAttestation =
        when (result) {
            is AttestationResult.Attested -> {
                result.stored
            }

            is AttestationResult.Replayed -> {
                result.stored
            }

            AttestationResult.RunNotFound -> {
                throw AttestationRefusal(AttestationRefusalCode.NOT_FOUND)
            }

            is AttestationResult.Refused -> {
                throw AttestationRefusal(
                    when (result.reason) {
                        UseCaseRefusal.SEPARATION_OF_DUTIES -> AttestationRefusalCode.SEPARATION_OF_DUTIES
                        UseCaseRefusal.UNEXPLAINED_VARIANCE -> AttestationRefusalCode.UNEXPLAINED_VARIANCE
                    },
                )
            }
        }

    private fun attestation(stored: StoredAttestation) =
        Attestation(
            attestedBy = stored.attestedBy,
            attestedAt = stored.attestedAt.atOffset(ZoneOffset.UTC),
            soleAttester = stored.soleAttester?.let { AttestationSoleAttester(it.acknowledgedBy, it.acknowledgedOn) },
        )

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
                view.attestation?.let(::attestation),
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
