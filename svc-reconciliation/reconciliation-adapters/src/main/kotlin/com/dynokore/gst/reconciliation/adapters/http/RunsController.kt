package com.dynokore.gst.reconciliation.adapters.http

import com.dynokore.gst.reconciliation.application.run.GetRun
import com.dynokore.gst.reconciliation.application.run.ListRuns
import com.dynokore.gst.reconciliation.application.run.RecordedRun
import com.dynokore.gst.reconciliation.contract.api.RunsApi
import com.dynokore.gst.reconciliation.contract.model.ErrorEnvelope
import com.dynokore.gst.reconciliation.contract.model.Run
import com.dynokore.gst.reconciliation.contract.model.RunPage
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.ZoneOffset
import java.util.UUID

/**
 * The run, work-paper and attestation operations of the reconciliation contract (TPL-08). `listRuns` and `getRun`
 * are implemented (INV-T-24376, INV-REQ-656); every other operation answers 501 until the task named for it in
 * `operations.tsv` overrides its method: the generated interface's default is NOT_IMPLEMENTED.
 *
 * Who may call is decided by the generated check of the operation, not here. Each read runs in one tenant
 * transaction. A refusal is a client error (400 or 404), never a 5xx; a limit is never clamped.
 */
@RestController
open class RunsController(
    private val listRuns: ListRuns,
    private val getRun: GetRun,
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
        // RecordedRun carries no form, so each item reads its form from the stored run in the same transaction
        val page =
            try {
                transactions.inTransaction {
                    val view = listRuns.execute(tenant, entityRef, periodRef, limit, cursor)
                    val items =
                        view.items.map { recorded ->
                            val snapshot = checkNotNull(getRun.execute(tenant, recorded.runId)) { "run vanished" }
                            run(recorded, snapshot.returnForm)
                        }
                    RunPage(items = items, nextCursor = view.nextCursor)
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
        return ResponseEntity.ok(run(snapshot.run, snapshot.returnForm))
    }

    private fun run(
        recorded: RecordedRun,
        form: String?,
    ) = Run(
        runId = UUID.fromString(recorded.runId),
        entityRef = recorded.entityRef,
        periodRef = recorded.periodRef,
        completedAt = recorded.completedAt.atOffset(ZoneOffset.UTC),
        returnVersion = recorded.returnVersion,
        returnForm = form?.let { Run.ReturnForm.forValue(it) },
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
