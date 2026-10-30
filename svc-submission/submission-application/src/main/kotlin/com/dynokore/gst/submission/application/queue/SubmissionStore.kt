package com.dynokore.gst.submission.application.queue

import com.dynokore.platform.tenancykernel.TenantId

/**
 * The state rows of `submission` the queue slices write, behind a port so `application` stays free of jOOQ (ARC-03).
 * Every method runs in the caller's open tenant transaction (ARC-21); none opens or commits one.
 */
interface SubmissionStore {
    /**
     * Moves the version from `READY` to `QUEUED` with one UPDATE whose WHERE clause holds the state `READY`, and
     * returns the number of rows updated: 0 when the row is missing or in another state.
     */
    fun moveReadyToQueued(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): Int
}
