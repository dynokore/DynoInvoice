package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.application.ApproveRegulatoryPackResult
import com.dynokore.gst.rules.contract.model.RegulatoryPack
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode

/**
 * The answer of `approveRegulatoryPack` against the pinned contract: 200 `RegulatoryPack` APPROVED for an approval and
 * for a repeat, 403 SRT-004 (a detail that names no person) for a denied permission and for self-approval, 404 SRT-005,
 * 409 SRT-008 for a pack that is not STAGED, has no dry run or has an unknown requester. The 503 SRT-013 of an
 * unavailable permission dependency is [unavailable], raised by the permission port before anything is read or written.
 */
object RegulatoryPackApprovalMapping {
    private const val DENIED_DETAIL = "the approval is not permitted"

    /** The refusal a result stands for; null for [ApproveRegulatoryPackResult.Approved] and `AlreadyApproved`. */
    fun refusalOf(result: ApproveRegulatoryPackResult): MappingRefusal? =
        when (result) {
            ApproveRegulatoryPackResult.Approved, ApproveRegulatoryPackResult.AlreadyApproved -> {
                null
            }

            ApproveRegulatoryPackResult.Denied, ApproveRegulatoryPackResult.SelfApproval -> {
                closed(ClosedErrorCode.ACCESS_DENIED, DENIED_DETAIL)
            }

            ApproveRegulatoryPackResult.NotFound -> {
                closed(ClosedErrorCode.NOT_FOUND, null)
            }

            ApproveRegulatoryPackResult.NotStaged -> {
                closed(ClosedErrorCode.STALE_AGGREGATE, "the pack is not STAGED")
            }

            ApproveRegulatoryPackResult.NoDryRun -> {
                closed(ClosedErrorCode.STALE_AGGREGATE, "no completed dry run exists for the pack")
            }

            ApproveRegulatoryPackResult.RequesterUnknown -> {
                closed(ClosedErrorCode.STALE_AGGREGATE, "the user who staged the pack is not recorded")
            }
        }

    /** The pack as the contract names it after an approval. */
    fun approved(
        packType: String,
        version: String,
    ) = RegulatoryPack(packType = packType, version = version, status = RegulatoryPack.Status.APPROVED)

    private fun closed(
        code: ClosedErrorCode,
        detail: String?,
    ) = MappingRefusal(code.httpStatus, code.code, code.title, detail)
}
