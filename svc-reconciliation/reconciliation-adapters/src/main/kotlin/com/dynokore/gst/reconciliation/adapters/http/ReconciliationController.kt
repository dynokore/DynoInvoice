package com.dynokore.gst.reconciliation.adapters.http

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.reconciliation.application.run.GetReconciliation
import com.dynokore.gst.reconciliation.application.workpaper.AnnotateVariance
import com.dynokore.gst.reconciliation.application.workpaper.AnnotationResult
import com.dynokore.gst.reconciliation.application.workpaper.StoredAnnotation
import com.dynokore.gst.reconciliation.contract.api.ReconciliationApi
import com.dynokore.gst.reconciliation.contract.model.AnnotateVarianceRequest
import com.dynokore.gst.reconciliation.contract.model.ErrorEnvelope
import com.dynokore.gst.reconciliation.contract.model.Reconciliation
import com.dynokore.gst.reconciliation.contract.model.VarianceAnnotation
import com.dynokore.gst.reconciliation.domain.annotation.AnnotationRefusal
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.ZoneOffset
import java.util.UUID

/**
 * The reconciliation view and variance annotation operations of the reconciliation contract (TPL-08).
 * `getReconciliation` is implemented (INV-T-24421, INV-REQ-657): 200 with the latest completed run, 503 PER-016 with
 * `Retry-After` while a return is recorded and no run has completed, 200 with PER-008 only when no return is
 * recorded. `annotateVariance` is implemented (INV-T-24380, INV-REQ-633): one tenant transaction, the acting user ID
 * is the actor the runtime binds with the tenant. Every other operation answers 501 until the task named for it in
 * `operations.tsv` overrides its method:
 * the generated interface's default is NOT_IMPLEMENTED.
 *
 * Who may call is decided by the generated check of the operation, not here. The read runs in one tenant
 * transaction; a port failure is never mapped to any of the three answers.
 */
@RestController
open class ReconciliationController(
    private val getReconciliation: GetReconciliation,
    private val transactions: TransactionRunner,
    private val tenants: TenantContext,
    private val retryAfter: PendingRetryAfter,
    private val annotateVariance: AnnotateVariance,
) : ReconciliationApi {
    override fun getReconciliation(
        entityRef: String,
        periodRef: String,
    ): ResponseEntity<Reconciliation> {
        val tenant = tenants.current().toString()
        val view = transactions.inTransaction { getReconciliation.execute(tenant, entityRef, periodRef) }
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
        return ReconciliationResponses.answer(view, entityRef, periodRef, retryAfter, correlation)
    }

    override fun annotateVariance(
        runId: UUID,
        box: String,
        annotateVarianceRequest: AnnotateVarianceRequest,
    ): ResponseEntity<VarianceAnnotation> {
        val tenant = tenants.current().toString()
        val actor = checkNotNull(tenants.currentActor()) { "no acting user is bound" }
        val result =
            transactions.inTransaction {
                annotateVariance.execute(
                    tenant,
                    runId.toString(),
                    box,
                    annotateVarianceRequest.varianceClass,
                    annotateVarianceRequest.references,
                    actor,
                )
            }
        return when (result) {
            is AnnotationResult.Accepted -> ResponseEntity.ok(annotation(result.stored))
            is AnnotationResult.Replayed -> ResponseEntity.ok(annotation(result.stored))
            is AnnotationResult.Refused -> refused(result.reason)
            AnnotationResult.BoxNotFound -> closed(ClosedErrorCode.NOT_FOUND, "No such Box in the run.")
            AnnotationResult.RunNotFound -> closed(ClosedErrorCode.NOT_FOUND, "No such run.")
        }
    }

    private fun refused(reason: AnnotationRefusal): ResponseEntity<VarianceAnnotation> =
        when (reason) {
            AnnotationRefusal.CLASS_NOT_ASSIGNABLE -> {
                envelope(
                    ErrorCode.PER_015.code,
                    HttpStatus.UNPROCESSABLE_CONTENT.value(),
                    "Variance annotation refused",
                    "The variance class is not one a person may assign; nothing is recorded.",
                    "MANUAL",
                    "Tax operations",
                )
            }

            AnnotationRefusal.NO_REFERENCES -> {
                closed(ClosedErrorCode.CONTRACT_VIOLATION, "references must hold at least one entry.")
            }

            AnnotationRefusal.RUN_ATTESTED -> {
                error("RUN_ATTESTED has no ruled catalogue code for its 409 envelope")
            }
        }

    private fun annotation(stored: StoredAnnotation) =
        VarianceAnnotation(
            runId = UUID.fromString(stored.runId),
            box = stored.annotation.box,
            references = stored.annotation.references,
            annotatedAt = stored.annotatedAt.atOffset(ZoneOffset.UTC),
            varianceClass = stored.annotation.varianceClass?.value,
        )

    private fun <T : Any> closed(
        code: ClosedErrorCode,
        detail: String,
    ): ResponseEntity<T> =
        envelope(code.code, code.httpStatus, code.title, detail, code.disposition, code.remediationOwner)

    /** The body of an error response is the API-20 envelope; the generated signature names the success type only. */
    @Suppress("UNCHECKED_CAST", "LongParameterList")
    private fun <T : Any> envelope(
        code: String,
        status: Int,
        title: String,
        detail: String,
        disposition: String,
        owner: String,
    ): ResponseEntity<T> {
        val body =
            ErrorEnvelope(
                type = URI.create(Problems.typeOf(code)),
                title = title,
                status = status,
                code = code,
                correlationId = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}",
                detail = detail,
                severity = ErrorEnvelope.Severity.BLOCK,
                disposition = disposition,
                remediationOwner = owner,
            )
        return ResponseEntity
            .status(status)
            .header(HttpHeaders.CONTENT_TYPE, Problems.CONTENT_TYPE)
            .body(body) as ResponseEntity<T>
    }
}
