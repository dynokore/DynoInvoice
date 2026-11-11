package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.ordering.LifecycleState

/** [FailureReporting] over the catalogue, the code store and the signal port. */
class DefaultFailureReporting(
    private val catalogue: ErrorCatalogue,
    private val codes: FailureCodes,
    private val signals: FailureSignals,
) : FailureReporting {
    override fun attemptFailed(
        dispatch: Dispatch,
        code: String,
    ) {
        codes.record(dispatch.item.tenant, dispatch.docUuid, dispatch.version, dispatch.attemptNumber, code)
    }

    override fun versionFailed(
        dispatch: Dispatch,
        state: LifecycleState,
        rejectedCode: String?,
    ) {
        val code =
            when (state) {
                LifecycleState.REJECTED -> {
                    requireNotNull(rejectedCode) { "a REJECTED report needs the first rejection code" }
                }

                LifecycleState.DEAD_LETTERED -> {
                    checkNotNull(codes.lastOf(dispatch.item.tenant, dispatch.docUuid, dispatch.version)) {
                        "no failure code recorded for ${dispatch.docUuid} version ${dispatch.version}"
                    }
                }

                else -> {
                    throw IllegalArgumentException("a failure is reported for REJECTED or DEAD_LETTERED, not $state")
                }
            }
        signals.stage(SubmissionFailure.of(dispatch.docUuid, dispatch.version, state, code, catalogue.entryOf(code)))
    }
}
