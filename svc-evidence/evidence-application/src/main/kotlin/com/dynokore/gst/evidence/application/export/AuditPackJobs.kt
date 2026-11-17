package com.dynokore.gst.evidence.application.export

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.util.UUID

/** The state of an audit-pack job (INV-REQ-988): it starts PENDING and ends READY or FAILED. */
enum class AuditPackState { PENDING, READY, FAILED }

/**
 * One audit-pack job for an entity and a period. [requestedBy] is the acting user ID, never a role.
 * [readyAt], [downloadUri] and [contents] are set only in READY; [failureReason] only in FAILED.
 */
data class AuditPackExportJob(
    val tenant: TenantId,
    val packId: UUID,
    val entityRef: String,
    val periodRef: String,
    val state: AuditPackState,
    val requestedAt: Instant,
    val requestedBy: String,
    val readyAt: Instant? = null,
    val downloadUri: String? = null,
    val contents: List<String> = emptyList(),
    val failureReason: String? = null,
)

/** The store of audit-pack jobs: one job per tenant, entity and period; a finished job never changes. */
interface AuditPackJobs {
    /** Registers a job; a repeat for the same entity and period returns the stored job unchanged. */
    fun register(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
        requestedBy: String,
    ): AuditPackExportJob

    fun find(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
        packId: UUID,
    ): AuditPackExportJob?

    /** Moves a PENDING job to READY; a READY or FAILED job changes no row and the stored job is returned. */
    fun markReady(
        job: AuditPackExportJob,
        downloadUri: String,
        contents: List<String>,
    ): AuditPackExportJob

    /** Moves a PENDING job to FAILED; a READY or FAILED job changes no row and the stored job is returned. */
    fun markFailed(
        job: AuditPackExportJob,
        reason: String,
    ): AuditPackExportJob
}
