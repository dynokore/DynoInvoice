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

    /**
     * Despatch-time write (INV-REQ-724, slice E4f): upserts on (tenant, docUuid, version) and sets only `state` to
     * [SubmissionIndexState.SENT], only where `state` is still null. Creates a row holding only the key and the state
     * when none exists. True when the state was set, false when a state was already set (never lowers a state).
     */
    fun markDespatched(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ): Boolean

    /**
     * Upserts the acknowledgement columns on (tenant, docUuid, version): sets ack_id, acknowledged_at and state
     * ACKNOWLEDGED (replacing SENT or null) only where ack_id is still null; a new row holds only these columns and
     * stays a partial row. Never touches the document columns. True when written, false when an acknowledgement was
     * already set.
     */
    fun upsertAcknowledgement(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
        ackId: String,
        acknowledgedAt: Instant,
    ): Boolean
}
