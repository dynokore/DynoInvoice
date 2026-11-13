package com.dynokore.gst.consoleBff.application.exceptions

/** Facts of a PayloadRejected or SubmissionFailed payload that open one exception row (INV-AC-21057). */
data class FailureFacts(
    val docUuid: String,
    val docsequence: Long,
    val entityRef: String,
    val code: String,
    val remediationOwner: String,
    val disposition: String,
)

/**
 * Reads the PayloadRejected payload. A missing or mistyped required fact is a `ContractViolation`. The envelope values
 * are used only when the payload has none (CloudEvents attributes as context, fail closed).
 */
interface PayloadRejectedReader {
    fun read(
        dataJson: String,
        envelopeEntityRef: String?,
        envelopeDocSequence: Long?,
    ): FailureFacts
}

/** Reads the SubmissionFailed payload; the row code falls back to `failureClass`. Same fail-closed rules. */
interface SubmissionFailedReader {
    fun read(
        dataJson: String,
        envelopeEntityRef: String?,
        envelopeDocSequence: Long?,
    ): FailureFacts
}
