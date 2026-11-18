package com.dynokore.gst.reconciliation.adapters.http

import com.dynokore.gst.reconciliation.application.returns.StoredReturn
import com.dynokore.gst.reconciliation.application.returns.UploadGstReturn
import com.dynokore.gst.reconciliation.application.returns.UploadResult
import com.dynokore.gst.reconciliation.contract.api.ReturnsApi
import com.dynokore.gst.reconciliation.contract.model.ErrorEnvelope
import com.dynokore.gst.reconciliation.contract.model.GstReturnRecord
import com.dynokore.gst.reconciliation.contract.model.UploadGstReturnRequest
import com.dynokore.gst.reconciliation.domain.returns.RefusalReason
import com.dynokore.gst.reconciliation.domain.returns.ReturnForm
import com.dynokore.gst.reconciliation.domain.returns.ReturnUpload
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
 * The filed-return intake operations of the reconciliation contract (TPL-08). `uploadGstReturn` is implemented
 * (INV-T-24367, INV-REQ-653); every other operation answers 501 until the task named for it in `operations.tsv`
 * overrides its method: the generated interface's default is NOT_IMPLEMENTED.
 *
 * The acting user ID is the actor the platform bound with the tenant of the verified principal
 * ([TenantContext.currentActor], the `sub` of the token), never a role and never the free-text preparer. Who may
 * upload is decided by the generated permission check of the operation, not here.
 */
@RestController
open class ReturnsController(
    private val upload: UploadGstReturn,
    private val transactions: TransactionRunner,
    private val tenants: TenantContext,
) : ReturnsApi {
    override fun uploadGstReturn(
        entityRef: String,
        periodRef: String,
        uploadGstReturnRequest: UploadGstReturnRequest,
    ): ResponseEntity<GstReturnRecord> {
        val tenant = tenants.current().toString()
        val actor = checkNotNull(tenants.currentActor()?.takeIf { it.isNotBlank() }) { "no acting user is bound" }
        val request = uploadGstReturnRequest
        val result =
            transactions.inTransaction {
                upload.execute(
                    tenant = tenant,
                    entityRef = entityRef,
                    periodRef = periodRef,
                    upload =
                        ReturnUpload(
                            version = request.version,
                            form = ReturnForm.valueOf(request.formType.value),
                            filedAt = request.filedAt.toInstant(),
                            filingAcknowledgement = request.filingAcknowledgement,
                            amends = request.amends,
                        ),
                    boxes = request.boxes.associate { it.box to it.amount },
                    filingReference = request.filingReference,
                    preparer = request.preparer,
                    uploadedBy = actor,
                )
            }
        return when (result) {
            is UploadResult.Created -> {
                ResponseEntity.status(HttpStatus.CREATED).body(record(entityRef, periodRef, result.stored))
            }

            is UploadResult.Replayed -> {
                ResponseEntity.ok(record(entityRef, periodRef, result.stored))
            }

            is UploadResult.Refused -> {
                refusal(result.reason)
            }
        }
    }

    private fun record(
        entityRef: String,
        periodRef: String,
        stored: StoredReturn,
    ) = GstReturnRecord(
        entityRef = entityRef,
        periodRef = periodRef,
        version = stored.recorded.version,
        form = GstReturnRecord.Form.forValue(stored.recorded.form.value),
        filedAt = stored.recorded.filedAt.atOffset(ZoneOffset.UTC),
        recordedAt = stored.recordedAt.atOffset(ZoneOffset.UTC),
        formType = GstReturnRecord.FormType.forValue(stored.recorded.form.value),
        filingReference = stored.filingReference,
        preparer = stored.preparer,
        amends = stored.recorded.amends,
    )

    /**
     * A refusal is a client error, never a 5xx: PER-014 is the 422 of the contract (BLOCK, MANUAL, Tax operations); a
     * version lower than a recorded one is a stale write (optimistic concurrency), RFC 9110 409 with the catalogue code
     * SRT-008, through the contract `default` response.
     * The body of an error response is the envelope; the generated signature names the success type only.
     */
    @Suppress("UNCHECKED_CAST")
    private fun refusal(reason: RefusalReason): ResponseEntity<GstReturnRecord> {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
        val envelope =
            when (reason) {
                RefusalReason.MISSING_FILING_ACKNOWLEDGEMENT -> {
                    ErrorEnvelope(
                        type = URI.create(Problems.typeOf(MISSING_ACKNOWLEDGEMENT_CODE)),
                        title = "Filing acknowledgement missing",
                        status = UNPROCESSABLE,
                        code = MISSING_ACKNOWLEDGEMENT_CODE,
                        correlationId = correlation,
                        detail = "The upload carries no filing acknowledgement; nothing is recorded.",
                        severity = ErrorEnvelope.Severity.BLOCK,
                        disposition = "MANUAL",
                        remediationOwner = "Tax operations",
                    )
                }

                RefusalReason.VERSION_LOWER_THAN_RECORDED -> {
                    val closed = ClosedErrorCode.STALE_AGGREGATE
                    ErrorEnvelope(
                        type = URI.create(Problems.typeOf(closed.code)),
                        title = closed.title,
                        status = closed.httpStatus,
                        code = closed.code,
                        correlationId = correlation,
                        detail = "A higher version of this return is already recorded; nothing is recorded.",
                        severity = ErrorEnvelope.Severity.BLOCK,
                        disposition = closed.disposition,
                        remediationOwner = closed.remediationOwner,
                    )
                }
            }
        return ResponseEntity
            .status(envelope.status)
            .header(HttpHeaders.CONTENT_TYPE, Problems.CONTENT_TYPE)
            .body(envelope) as ResponseEntity<GstReturnRecord>
    }

    private companion object {
        const val MISSING_ACKNOWLEDGEMENT_CODE = "PER-014"
        const val UNPROCESSABLE = 422
    }
}
