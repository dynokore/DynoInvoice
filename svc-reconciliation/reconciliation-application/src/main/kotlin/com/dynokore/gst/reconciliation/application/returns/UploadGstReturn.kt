package com.dynokore.gst.reconciliation.application.returns

import com.dynokore.gst.reconciliation.domain.returns.RecordedReturn
import com.dynokore.gst.reconciliation.domain.returns.RefusalReason
import com.dynokore.gst.reconciliation.domain.returns.ReturnUpload
import com.dynokore.gst.reconciliation.domain.returns.ReturnVersions
import com.dynokore.gst.reconciliation.domain.returns.UploadDecision
import java.math.BigDecimal
import java.time.Clock

sealed interface UploadResult {
    data class Created(
        val stored: StoredReturn,
    ) : UploadResult

    data class Replayed(
        val stored: StoredReturn,
    ) : UploadResult

    data class Refused(
        val reason: RefusalReason,
    ) : UploadResult
}

/**
 * Records a return upload as a new immutable version (INV-REQ-653): idempotent by the natural key (conditional insert),
 * the event and the run trigger only for the call that inserted. Opens no transaction; the caller does.
 */
class UploadGstReturn(
    private val records: ReturnRecords,
    private val publisher: GstReturnRecordedPublisher,
    private val trigger: RunTrigger,
    private val clock: Clock,
) {
    @Suppress("LongParameterList")
    fun execute(
        tenant: String,
        entityRef: String,
        periodRef: String,
        upload: ReturnUpload,
        boxes: Map<String, BigDecimal>,
        filingReference: String?,
        preparer: String?,
        uploadedBy: String,
    ): UploadResult {
        require(uploadedBy.isNotBlank()) { "uploadedBy must be the acting user ID" }
        val stored = records.recorded(tenant, entityRef, periodRef)
        return when (val decision = ReturnVersions.decide(stored.map { it.recorded }, upload)) {
            is UploadDecision.Refused -> {
                UploadResult.Refused(decision.reason)
            }

            is UploadDecision.Repeat -> {
                UploadResult.Replayed(stored.first { it.recorded.version == decision.original.version })
            }

            UploadDecision.Accepted -> {
                val candidate =
                    StoredReturn(
                        recorded =
                            RecordedReturn(
                                version = upload.version,
                                form = upload.form,
                                filedAt = upload.filedAt,
                                filingAcknowledgement = requireNotNull(upload.filingAcknowledgement),
                                amends = upload.amends,
                            ),
                        boxes = boxes,
                        filingReference = filingReference,
                        preparer = preparer,
                        uploadedBy = uploadedBy,
                        recordedAt = clock.instant(),
                    )
                if (records.insertIfAbsent(tenant, entityRef, periodRef, candidate)) {
                    publisher.publish(tenant, entityRef, periodRef, candidate)
                    trigger.enqueue(tenant, entityRef, periodRef, candidate.recorded.version)
                    UploadResult.Created(candidate)
                } else {
                    val winner =
                        records.recorded(tenant, entityRef, periodRef).first { it.recorded.version == upload.version }
                    UploadResult.Replayed(winner)
                }
            }
        }
    }
}
