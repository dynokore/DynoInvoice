package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.ordering.LifecycleState

/**
 * The members of a `SubmissionFailed` signal (INV-REQ-940). [failureClass] is the catalogue code recorded for the
 * attempt; [code], [remediationOwner] and [disposition] are read from the catalogue entry of that code and are absent
 * (null) when the entry is absent or carries no value.
 */
data class SubmissionFailure(
    val docUuid: String,
    val version: Int,
    val failureClass: String,
    val retryDisposition: String,
    val code: String?,
    val remediationOwner: String?,
    val disposition: String?,
) {
    companion object {
        /** Dead-lettered: the retry bound is exhausted, so an operator acts (dead-letter practice). */
        private const val DEAD_LETTERED_DISPOSITION = "MANUAL"

        /** Rejected without a catalogue disposition: an access-point rejection is never auto-retried. */
        private const val REJECTED_DEFAULT_DISPOSITION = "TERMINAL"

        /** @throws IllegalArgumentException when [state] is neither REJECTED nor DEAD_LETTERED. */
        fun of(
            docUuid: String,
            version: Int,
            state: LifecycleState,
            code: String,
            entry: CatalogueEntry?,
        ): SubmissionFailure {
            val retry =
                when (state) {
                    LifecycleState.REJECTED -> entry?.disposition ?: REJECTED_DEFAULT_DISPOSITION

                    LifecycleState.DEAD_LETTERED -> DEAD_LETTERED_DISPOSITION

                    else -> throw IllegalArgumentException(
                        "a failure is reported for REJECTED or DEAD_LETTERED, not $state",
                    )
                }
            return SubmissionFailure(
                docUuid = docUuid,
                version = version,
                failureClass = code,
                retryDisposition = retry,
                code = entry?.code,
                remediationOwner = entry?.owner,
                disposition = entry?.disposition,
            )
        }
    }
}
