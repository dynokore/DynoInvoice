package com.dynokore.gst.evidence.application.retention

import com.dynokore.gst.evidence.domain.retention.LegalHold
import com.dynokore.gst.evidence.domain.retention.NoticeWindow
import java.time.Instant
import java.time.LocalDate

enum class PartitionState { ACTIVE, DROPPED }

/** A whole date-range partition of one entity; [tailHashes] are the chain tail hashes for the deletion audit entry. */
data class PartitionRecord(
    val partitionId: String,
    val entityRef: String,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val state: PartitionState,
    val tailHashes: List<String>,
)

/** The notice sent to the custody holder and the window it opened. */
data class NoticeRecord(
    val notifiedAt: Instant,
    val window: NoticeWindow,
)

/** An approval of a deletion: the approving user ID (never a role) and the permission key it was decided under. */
data class Approval(
    val approverUserId: String,
    val permissionKey: String,
)

interface PartitionRegistry {
    fun find(partitionId: String): PartitionRecord?
}

interface HoldReader {
    fun activeCovering(
        tenant: String,
        entity: String,
        periodFrom: LocalDate,
        periodTo: LocalDate,
    ): List<LegalHold>
}

interface RetentionEndReader {
    /** The retention end, or null when it cannot be determined. */
    fun retentionEnd(
        tenant: String,
        partitionId: String,
    ): Instant?
}

interface NoticeReader {
    /** The notice record, or null when no notice row exists. */
    fun window(
        tenant: String,
        partitionId: String,
    ): NoticeRecord?
}

interface ApprovalReader {
    fun approvals(
        tenant: String,
        partitionId: String,
    ): List<Approval>
}

interface DeletionIntegrity {
    fun allowsDeletion(
        tenant: String,
        partitionId: String,
    ): Boolean
}

interface DeletionAudit {
    /** Appends the deletion audit entry and returns its reference. */
    fun append(
        tenant: String,
        partitionId: String,
        actorUserId: String,
        tailHashes: List<String>,
    ): String
}

interface PartitionLifecycle {
    fun detach(
        partitionId: String,
        auditRef: String,
        retentionEnd: Instant,
    )

    /** Archives the detached partition and returns the archive reference. */
    fun archive(partitionId: String): String

    fun drop(partitionId: String)
}
