package com.dynokore.gst.evidence.application.chain

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The document columns of one `submission_index` row (INV-REQ-724, slice E4a). */
data class SubmissionIndexRow(
    val docUuid: UUID,
    val version: Int,
    val entityRef: String,
    val periodRef: String,
    val documentNumber: String?,
    val documentDate: LocalDate,
    val counterpartyUen: String?,
    val recordedAt: Instant,
)

/** State values written to `submission_index.state`, verbatim published submission lifecycle values. */
object SubmissionIndexState {
    const val SENT = "SENT"
    const val ACKNOWLEDGED = "ACKNOWLEDGED"
}

/** The reverse-lookup read table, one row per document version. */
interface SubmissionIndexStore {
    /**
     * Upserts the document columns on (tenant, docUuid, version), setting each only where it is still null and never
     * touching ack_id, acknowledged_at or state. True when the document columns were written, false when they already
     * were.
     */
    fun upsertDocument(
        tenant: TenantId,
        row: SubmissionIndexRow,
    ): Boolean
}
