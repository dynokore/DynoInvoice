package com.dynokore.gst.evidence.application.retention

import com.dynokore.gst.evidence.domain.retention.DeletionGate
import com.dynokore.gst.evidence.domain.retention.NoticeWindowGate
import java.time.Clock
import java.time.Instant

enum class DeleteAtRetentionEndResult {
    DELETED,
    RETENTION_NOT_ENDED,
    HOLD_IN_FORCE,
    NOTICE_MISSING,
    WINDOW_OPEN,
    APPROVALS_MISSING,
    CHAIN_VERIFICATION_FAILED,
    ALREADY_DELETED,
    NOT_FOUND,
}

/**
 * Decides and orders a retention-end deletion (INV-REQ-639). The first failing check is the result and every refusal
 * returns before any audit or lifecycle call; on success the audit entry comes first (NIST SP 800-53 AU-2), then
 * detach, archive and drop. The actor is a user ID.
 */
@Suppress("LongParameterList") // one port per fact the ruled design names; each is a separate store
class DeleteAtRetentionEnd(
    private val registry: PartitionRegistry,
    private val holds: HoldReader,
    private val retentionEnds: RetentionEndReader,
    private val notices: NoticeReader,
    private val approvals: ApprovalReader,
    private val integrity: DeletionIntegrity,
    private val audit: DeletionAudit,
    private val lifecycle: PartitionLifecycle,
    private val clock: Clock,
) {
    fun execute(
        tenant: String,
        actorUserId: String,
        partitionId: String,
    ): DeleteAtRetentionEndResult {
        val partition = registry.find(partitionId)
        return when {
            partition == null -> DeleteAtRetentionEndResult.NOT_FOUND
            partition.state == PartitionState.DROPPED -> DeleteAtRetentionEndResult.ALREADY_DELETED
            else -> decideAndDelete(tenant, actorUserId, partition)
        }
    }

    private fun decideAndDelete(
        tenant: String,
        actorUserId: String,
        partition: PartitionRecord,
    ): DeleteAtRetentionEndResult {
        val partitionId = partition.partitionId
        val now = clock.instant()
        var retentionEnd: Instant? = null
        // Ordered, lazy checks: the first failing one is the result, and none of them writes anything.
        val checks: List<() -> DeleteAtRetentionEndResult?> =
            listOf(
                {
                    retentionEnd = retentionEnds.retentionEnd(tenant, partitionId)
                    DeleteAtRetentionEndResult.RETENTION_NOT_ENDED.takeIf { notEnded(retentionEnd, now) }
                },
                { DeleteAtRetentionEndResult.HOLD_IN_FORCE.takeIf { holdInForce(tenant, partition) } },
                { noticeRefusal(tenant, partitionId, now) },
                { DeleteAtRetentionEndResult.APPROVALS_MISSING.takeIf { distinctApprovers(tenant, partitionId) < 2 } },
                { DeleteAtRetentionEndResult.CHAIN_VERIFICATION_FAILED.takeIf { !chainOk(tenant, partitionId) } },
            )
        val refusal = checks.firstNotNullOfOrNull { it() }
        val end = retentionEnd
        if (refusal == null && end != null) {
            val auditRef = audit.append(tenant, partitionId, actorUserId, partition.tailHashes)
            lifecycle.detach(partitionId, auditRef, end)
            lifecycle.archive(partitionId)
            lifecycle.drop(partitionId)
        }
        return refusal ?: DeleteAtRetentionEndResult.DELETED
    }

    private fun notEnded(
        retentionEnd: Instant?,
        now: Instant,
    ): Boolean = retentionEnd == null || retentionEnd.isAfter(now)

    private fun holdInForce(
        tenant: String,
        p: PartitionRecord,
    ): Boolean =
        holds
            .activeCovering(tenant, p.entityRef, p.periodFrom, p.periodTo)
            .any { it.coversPartition(p.entityRef, p.periodFrom, p.periodTo) }

    private fun chainOk(
        tenant: String,
        partitionId: String,
    ): Boolean = integrity.allowsDeletion(tenant, partitionId)

    private fun noticeRefusal(
        tenant: String,
        partitionId: String,
        now: Instant,
    ): DeleteAtRetentionEndResult? {
        val notice = notices.window(tenant, partitionId) ?: return DeleteAtRetentionEndResult.NOTICE_MISSING
        return when (NoticeWindowGate.decide(notice.notifiedAt, notice.window, false, now)) {
            is DeletionGate.WindowOpen -> DeleteAtRetentionEndResult.WINDOW_OPEN
            DeletionGate.HoldInForce -> error("unreachable: the hold flag passed to the gate is false")
            DeletionGate.MayProceedToCoApproval -> null
        }
    }

    private fun distinctApprovers(
        tenant: String,
        partitionId: String,
    ): Int =
        approvals
            .approvals(tenant, partitionId)
            .map { it.approverUserId }
            .toSet()
            .size
}
