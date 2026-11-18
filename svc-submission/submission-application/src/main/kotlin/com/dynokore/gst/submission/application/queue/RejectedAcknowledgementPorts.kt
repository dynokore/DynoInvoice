package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.transitions.RejectionReason
import com.dynokore.platform.tenancykernel.TenantId

/** The members of a REJECTED `AcknowledgementReceived` event the consumer reads. */
data class RejectedAcknowledgementData(
    val docUuid: String,
    val version: Int,
    val reasons: List<RejectionReason>,
)

/** Reads the event data JSON of a REJECTED `AcknowledgementReceived` (the JSON library stays in the adapters). */
fun interface RejectedAcknowledgementReader {
    fun read(dataJson: String): RejectedAcknowledgementData
}

/** Reads the outcome member: an absent member reads as ACCEPTED; a value other than ACCEPTED or REJECTED throws. */
fun interface AcknowledgementOutcomeReader {
    fun outcomeOf(dataJson: String): String
}

/** The number of the latest attempt of a version, null when the version never had an attempt. */
fun interface LatestAttempt {
    fun numberOf(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): Int?
}
