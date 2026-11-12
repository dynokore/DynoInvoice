package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.pack.PackStatus

/** A regulatory pack version as the registry holds it for the approval decision. */
data class RegulatoryPackRecord(
    val status: PackStatus,
    /** The user ID that staged the pack, or null when the registry does not know it. */
    val stagedByUserId: String?,
    val dryRunDeltaExists: Boolean,
)

/** The regulatory pack registry as the approval use case sees it. */
interface RegulatoryPackStore {
    fun find(
        packType: String,
        version: String,
    ): RegulatoryPackRecord?

    /** Sets APPROVED and records the approving user ID (never a role) in one atomic write. */
    fun markApproved(
        packType: String,
        version: String,
        approvedBy: String,
    )
}

/** A scoped permission decision by AuthorityReplica.decide (wired in INV-T-4975); never a role name. */
fun interface ApprovalPermissionPort {
    fun mayApprove(approver: String): Boolean
}
