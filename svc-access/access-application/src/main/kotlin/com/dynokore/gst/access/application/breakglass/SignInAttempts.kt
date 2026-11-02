package com.dynokore.gst.access.application.breakglass

import com.dynokore.gst.access.domain.breakglass.SignInBackoff

/**
 * The back-off state of one unlock of the bound tenant (INV-AC-9184). Called inside the transaction of the use case, so
 * the decision and the update are one unit of work.
 */
interface SignInAttempts {
    /** Reads the state of [unlockRef] for update; [SignInBackoff.INITIAL] when none was saved. */
    fun readForUpdate(unlockRef: String): SignInBackoff

    fun save(
        unlockRef: String,
        state: SignInBackoff,
    )
}
