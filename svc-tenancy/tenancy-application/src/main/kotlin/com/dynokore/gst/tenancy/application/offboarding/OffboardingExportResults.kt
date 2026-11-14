package com.dynokore.gst.tenancy.application.offboarding

import com.dynokore.gst.tenancy.domain.ExportManifest
import com.dynokore.gst.tenancy.domain.OffboardingScope
import com.dynokore.gst.tenancy.domain.OffboardingState
import java.time.Instant

/** An offboarding record with the instant it was requested (the table's created_at). */
data class StoredOffboarding(
    val record: OffboardingRecord,
    val createdAt: Instant,
)

/** The port that applies an export result to a stored record; every method runs in the caller's transaction. */
interface OffboardingExportResults {
    fun find(offboardingRef: String): StoredOffboarding?

    /** Sets [state], [manifest] and [failureReason]; true only when a REQUESTED row changed. */
    fun applyResult(
        offboardingRef: String,
        state: OffboardingState,
        manifest: ExportManifest,
        failureReason: String?,
    ): Boolean

    /** The REQUESTED record of this scope and entity, else null. */
    fun requestedFor(
        scope: OffboardingScope,
        entityRef: String?,
    ): StoredOffboarding?
}

/** Expires an overdue REQUESTED record of the scope; true when one was marked FAILED. */
fun interface OffboardingExpiry {
    fun expireOverdue(
        scope: OffboardingScope,
        entityRef: String?,
    ): Boolean
}
