package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId

/** One entry of the error-code catalogue; [disposition] is the name of the catalogue disposition, null when none. */
data class CatalogueEntry(
    val code: String,
    val owner: String,
    val disposition: String?,
)

/** The error-code catalogue, read and never copied. */
fun interface ErrorCatalogue {
    fun entryOf(code: String): CatalogueEntry?
}

/** The failure code recorded for each failed attempt, at the point where its outcome is classified. */
interface FailureCodes {
    fun record(
        tenant: TenantId,
        docUuid: String,
        version: Int,
        attemptNumber: Int,
        code: String,
    )

    /** The code of the attempt with the highest attempt number, null when it has none. */
    fun lastOf(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): String?
}

/** Stages the `SubmissionFailed` signal through the transactional outbox, in the caller's transaction. */
fun interface FailureSignals {
    fun stage(failure: SubmissionFailure)
}

/** Reporting of failed attempts and failed versions (INV-REQ-940). */
interface FailureReporting {
    fun attemptFailed(
        dispatch: Dispatch,
        code: String,
    )

    /** [rejectedCode] is the first rejection reason code, used for REJECTED only. */
    fun versionFailed(
        dispatch: Dispatch,
        state: LifecycleState,
        rejectedCode: String?,
    )
}
