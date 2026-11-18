package com.dynokore.gst.reconciliation.adapters.http

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.reconciliation.application.run.ReconciliationView
import com.dynokore.gst.reconciliation.application.run.RunBox
import com.dynokore.gst.reconciliation.application.run.RunSnapshot
import com.dynokore.gst.reconciliation.application.run.RunUnmappedLine
import com.dynokore.gst.reconciliation.contract.model.BoxComparison
import com.dynokore.gst.reconciliation.contract.model.ErrorEnvelope
import com.dynokore.gst.reconciliation.contract.model.Reconciliation
import com.dynokore.gst.reconciliation.contract.model.ReconciliationReason
import com.dynokore.gst.reconciliation.contract.model.UnmappedLine
import com.dynokore.gst.reconciliation.contract.model.VarianceClass
import com.dynokore.gst.reconciliation.contract.model.VarianceShare
import com.dynokore.platform.serviceruntime.http.Problems
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.util.UUID

/** The `Retry-After` of the pending answer, in whole seconds (RFC 9110 10.2.3); never below 1. */
data class PendingRetryAfter(
    val seconds: Int,
) {
    init {
        require(seconds >= 1) { "pending-retry-after-seconds must be at least 1, was $seconds" }
    }
}

/**
 * The mapping of the three states of a period to the answer of `getReconciliation` (INV-T-24421, INV-REQ-657).
 * Pure: no repository access. Amounts have scale 2 and the contract writes them as decimal strings.
 */
object ReconciliationResponses {
    private const val NO_RETURN_MESSAGE = "No return is recorded for the period; no Box comparison is produced."
    private val ZERO = BigDecimal("0.00")

    fun answer(
        view: ReconciliationView,
        entityRef: String,
        periodRef: String,
        retryAfter: PendingRetryAfter,
        correlationId: String,
    ): ResponseEntity<Reconciliation> =
        when (view) {
            is ReconciliationView.Found -> ResponseEntity.ok(found(view.snapshot))
            is ReconciliationView.NoReturn -> ResponseEntity.ok(noReturn(entityRef, periodRef, view.code))
            is ReconciliationView.Pending -> pending(retryAfter, correlationId)
        }

    fun found(snapshot: RunSnapshot): Reconciliation =
        Reconciliation(
            entityRef = snapshot.run.entityRef,
            periodRef = snapshot.run.periodRef,
            boxes = snapshot.boxes.map(::box),
            unexplainedResidual = money(snapshot.unexplainedResidual),
            returnVersion = snapshot.run.returnVersion,
            returnForm = Reconciliation.ReturnForm.forValue(snapshot.run.returnForm),
            unmappedLines = snapshot.unmappedLines.map(::unmapped),
        )

    fun noReturn(
        entityRef: String,
        periodRef: String,
        code: String,
    ): Reconciliation =
        Reconciliation(
            entityRef = entityRef,
            periodRef = periodRef,
            boxes = emptyList(),
            unexplainedResidual = ZERO,
            reasons = listOf(ReconciliationReason(code = code, message = NO_RETURN_MESSAGE)),
        )

    /** 503 with the API-20 envelope, the catalogue code [ErrorCode.PER_016] and `Retry-After` in whole seconds. */
    @Suppress("UNCHECKED_CAST")
    fun pending(
        retryAfter: PendingRetryAfter,
        correlationId: String,
    ): ResponseEntity<Reconciliation> {
        val code = ErrorCode.PER_016.code
        val envelope =
            ErrorEnvelope(
                type = URI.create(Problems.typeOf(code)),
                title = "Reconciliation not readable yet",
                status = HttpStatus.SERVICE_UNAVAILABLE.value(),
                code = code,
                correlationId = correlationId,
                detail = "A return is recorded for the period and no run has completed yet; retry the read.",
                severity = ErrorEnvelope.Severity.BLOCK,
                disposition = "RETRY",
                remediationOwner = "Tax operations",
            )
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.CONTENT_TYPE, Problems.CONTENT_TYPE)
            .header(HttpHeaders.RETRY_AFTER, retryAfter.seconds.toString())
            .body(envelope) as ResponseEntity<Reconciliation>
    }

    private fun box(box: RunBox) =
        BoxComparison(
            box = box.box,
            submittedAmount = money(box.submittedAmount),
            filedAmount = money(box.filedAmount),
            variance = money(box.variance),
            varianceClasses =
                box.varianceClasses.map {
                    VarianceShare(varianceClass = VarianceClass.forValue(it.varianceClass), amount = money(it.amount))
                },
        )

    private fun unmapped(line: RunUnmappedLine) =
        UnmappedLine(
            docUuid = UUID.fromString(line.docUuid),
            version = line.version,
            lineNumber = line.lineNumber,
            taxCode = line.taxCode,
        )

    /** Scale 2; the contract writes it as a decimal string. More than two decimals is refused, never rounded. */
    private fun money(amount: BigDecimal): BigDecimal = amount.setScale(2, RoundingMode.UNNECESSARY)
}
