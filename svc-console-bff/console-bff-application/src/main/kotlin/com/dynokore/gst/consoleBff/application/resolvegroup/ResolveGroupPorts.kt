package com.dynokore.gst.consoleBff.application.resolvegroup

import com.dynokore.gst.consoleBff.application.override.OwnerReply

/** The scoped permission key that masterdata itself checks for the bulk resolution. */
const val MASTERDATA_EXCEPTION_RESOLVE = "masterdata-exception.resolve"

/** Decided per request by AuthorityReplica.decide in the adapter; no role is read. */
fun interface ResolveGroupAuthority {
    fun holds(
        tenantId: String,
        principalId: String,
        entityRef: String,
    ): Boolean
}

data class SubmittedMember(
    val docUuid: String,
    val state: String,
)

data class BoundaryResult(
    val checkedCount: Int,
    val submittedCount: Int,
    val submittedMembers: List<SubmittedMember>,
)

/** Read only: submission-svc checkSubmissionBoundary. */
fun interface SubmissionBoundary {
    fun check(docUuids: List<String>): OwnerReply<BoundaryResult>
}

data class ResolvedIdentifier(
    val scheme: String,
    val value: String,
)

data class BulkCommand(
    val entityRef: String,
    val idempotencyKey: String,
    val errorCode: String,
    val counterpartyRef: String,
    val docUuids: List<String>,
    val resolvedIdentifiers: List<ResolvedIdentifier>,
    val evidenceRef: String,
)

data class BulkOutcome(
    val resolvedCount: Int,
    val unchangedCount: Int,
)

/** The owner's write: masterdata bulkResolveCounterparty. Console-bff state is not written. */
fun interface MasterdataBulk {
    fun resolve(command: BulkCommand): OwnerReply<BulkOutcome>
}
