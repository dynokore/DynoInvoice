package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.gst.rules.domain.pack.RegulatoryCommand
import com.dynokore.gst.rules.domain.pack.RegulatoryPackLifecycle
import com.dynokore.gst.rules.domain.pack.RegulatoryPackLifecycle.Transition

/** The answer of the approval of a regulatory pack; every refusal is typed and changes nothing (INV-AC-20459). */
sealed interface ApproveRegulatoryPackResult {
    data object Approved : ApproveRegulatoryPackResult

    /** The pack is already APPROVED; a no-op. */
    data object AlreadyApproved : ApproveRegulatoryPackResult

    data object Denied : ApproveRegulatoryPackResult

    data object NotFound : ApproveRegulatoryPackResult

    data object NotStaged : ApproveRegulatoryPackResult

    data object NoDryRun : ApproveRegulatoryPackResult

    /** The staging user ID is unknown: fail closed. */
    data object RequesterUnknown : ApproveRegulatoryPackResult

    data object SelfApproval : ApproveRegulatoryPackResult
}

/** Approves a STAGED regulatory pack that has a dry-run delta, by a user other than the stager (INV-REQ-489). */
class ApproveRegulatoryPack(
    private val store: RegulatoryPackStore,
    private val permission: ApprovalPermissionPort,
) {
    fun execute(
        packType: String,
        version: String,
        approver: String,
    ): ApproveRegulatoryPackResult {
        val answer = refusal(packType, version, approver)
        if (answer == null) store.markApproved(packType, version, approver)
        return answer ?: ApproveRegulatoryPackResult.Approved
    }

    /** The first failing check in the ruled order, or null when the approval may be recorded. */
    private fun refusal(
        packType: String,
        version: String,
        approver: String,
    ): ApproveRegulatoryPackResult? {
        val allowed = permission.mayApprove(approver)
        val pack = if (allowed) store.find(packType, version) else null
        val stager = pack?.stagedByUserId
        val stageable =
            pack != null &&
                RegulatoryPackLifecycle.next(pack.status, RegulatoryCommand.APPROVE) is Transition.To
        return when {
            !allowed -> ApproveRegulatoryPackResult.Denied
            pack == null -> ApproveRegulatoryPackResult.NotFound
            pack.status == PackStatus.APPROVED -> ApproveRegulatoryPackResult.AlreadyApproved
            !stageable -> ApproveRegulatoryPackResult.NotStaged
            !pack.dryRunDeltaExists -> ApproveRegulatoryPackResult.NoDryRun
            stager == null -> ApproveRegulatoryPackResult.RequesterUnknown
            stager == approver -> ApproveRegulatoryPackResult.SelfApproval
            else -> null
        }
    }
}
