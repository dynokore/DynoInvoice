package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.gst.rules.domain.pack.RegulatoryCommand
import com.dynokore.gst.rules.domain.pack.RegulatoryPackLifecycle

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
        if (!permission.mayApprove(approver)) return ApproveRegulatoryPackResult.Denied
        val pack = store.find(packType, version) ?: return ApproveRegulatoryPackResult.NotFound
        if (pack.status == PackStatus.APPROVED) {
            return ApproveRegulatoryPackResult.AlreadyApproved
        }
        if (RegulatoryPackLifecycle.next(pack.status, RegulatoryCommand.APPROVE) !is RegulatoryPackLifecycle.Transition.To) {
            return ApproveRegulatoryPackResult.NotStaged
        }
        if (!pack.dryRunDeltaExists) return ApproveRegulatoryPackResult.NoDryRun
        val stager = pack.stagedByUserId ?: return ApproveRegulatoryPackResult.RequesterUnknown
        if (stager == approver) return ApproveRegulatoryPackResult.SelfApproval
        store.markApproved(packType, version, approver)
        return ApproveRegulatoryPackResult.Approved
    }
}
