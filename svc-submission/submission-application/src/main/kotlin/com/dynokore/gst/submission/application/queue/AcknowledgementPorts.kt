package com.dynokore.gst.submission.application.queue

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant

/** The members of an `AcknowledgementReceived` event the consumer reads; [outcome] is ACCEPTED when absent. */
data class AcknowledgementReceivedData(
    val docUuid: String,
    val version: Int,
    val acknowledgementId: String,
    val outcome: String = OUTCOME_ACCEPTED,
) {
    companion object {
        const val OUTCOME_ACCEPTED = "ACCEPTED"
        const val OUTCOME_REJECTED = "REJECTED"
    }
}

/** Reads the event data JSON of an `AcknowledgementReceived` (the JSON library stays in the adapters, ARC-03). */
fun interface AcknowledgementDataReader {
    /** @throws ContractViolation when a member is missing or malformed (dead letter at once). */
    fun read(dataJson: String): AcknowledgementReceivedData
}

/** Writes `acknowledgement_id` and `acknowledged_at` of a version row the caller has locked. */
fun interface AcknowledgementRecorder {
    fun record(
        tenant: TenantId,
        docUuid: String,
        version: Int,
        acknowledgementId: String,
        acknowledgedAt: Instant,
    )
}

/** The fact announced as `SubmissionAcknowledged`. */
class AcknowledgedFact(
    val docUuid: String,
    val version: Int,
    val acknowledgementId: String,
    val timestamp: Instant,
)

/** Stages the fact in the outbox of the open transaction (INV-ADR-283). */
fun interface AcknowledgedPublisher {
    fun stage(fact: AcknowledgedFact)
}

/** An `AcknowledgementReceived` outcome this slice does not handle; the runtime dead-letters the message. */
class UnsupportedAcknowledgementOutcome(
    outcome: String,
) : RuntimeException("AcknowledgementReceived outcome is not supported by this handler: $outcome")
