package com.dynokore.gst.submission.application.arrival

import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.scope.Verdict
import com.dynokore.platform.tenancykernel.TenantId

/** What the register holds about one document for the scope check (INV-REQ-722); a null verdict is a legacy entry. */
data class ScopeEntry(
    val entityRef: String,
    val periodRef: String,
    val kind: RowKind,
    val verdict: Verdict?,
    val precedingDocUuid: String?,
)

/** Reads the scope facts of a document; null when the tenant has no entry for the Doc UUID. */
interface ScopeFacts {
    fun of(
        tenant: TenantId,
        docUuid: String,
    ): ScopeEntry?
}

/** Moves a READY version to BLOCKED; returns the number of rows changed (1, or 0 when the version is not READY). */
interface ScopeBlocks {
    fun blockReady(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): Int
}
