package com.dynokore.gst.submission.application.boundary

import com.dynokore.gst.submission.application.listing.ListingActions
import com.dynokore.gst.submission.domain.boundary.SubmissionBoundary
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.util.UUID

/** One stored version of a document: the columns DOC_UUID, VERSION, ENTITY_REF and STATE of `submission.submission`. */
data class BoundaryVersion(
    val docUuid: UUID,
    val version: Int,
    val entityRef: String,
    val state: LifecycleState,
)

/** Reads the version rows of the given Doc UUIDs of one tenant; a Doc UUID with no row yields none (the adapter is slice 2). */
fun interface BoundaryVersionSource {
    fun versions(
        tenant: TenantId,
        docUuids: Collection<UUID>,
    ): List<BoundaryVersion>
}

data class CheckSubmissionBoundaryRequest(
    val tenant: TenantId,
    val principal: Principal,
    val docUuids: List<UUID>,
)

/** A document with at least one submitted version, shown with the state of its highest submitted version. */
data class SubmittedMember(
    val docUuid: UUID,
    val state: LifecycleState,
)

sealed interface CheckSubmissionBoundaryResult {
    data class Done(
        val checkedCount: Int,
        val members: List<SubmittedMember>,
    ) : CheckSubmissionBoundaryResult

    /** A decision was deny or the replica was unavailable: no count and no member (INV-REQ-1005, fail closed). */
    data object Forbidden : CheckSubmissionBoundaryResult

    data object TooManyDocuments : CheckSubmissionBoundaryResult
}

/**
 * INV-REQ-1005: how many members of a group have already submitted, and which. The scoped key
 * [ListingActions.VIEW_STATUS] is decided by the replica once per distinct entity of the found members (INV-Q-10027);
 * no role name is read. Any deny or unavailable replica refuses the whole call (OWASP ASVS V4, deny by default).
 */
class CheckSubmissionBoundary(
    private val replica: AuthorityReplica,
    private val source: BoundaryVersionSource,
    private val maxDocuments: Int,
) {
    init {
        require(maxDocuments >= 1) { "maxDocuments must be at least 1: $maxDocuments" }
    }

    fun handle(
        request: CheckSubmissionBoundaryRequest,
        deadline: Deadline,
    ): CheckSubmissionBoundaryResult {
        val docs = request.docUuids.distinct()
        if (docs.size > maxDocuments) return CheckSubmissionBoundaryResult.TooManyDocuments
        val rows = source.versions(request.tenant, docs)
        val byDoc = rows.groupBy { it.docUuid }
        val entities = docs.flatMap { byDoc[it].orEmpty() }.map { it.entityRef }.distinct()
        for (entity in entities) {
            val decision =
                replica.decide(
                    AuthorizationRequest(request.tenant, request.principal, entity, ListingActions.VIEW_STATUS),
                    deadline,
                )
            if (!decision.allowed || decision.reason == DecisionReason.AUTHORITY_UNAVAILABLE) {
                return CheckSubmissionBoundaryResult.Forbidden
            }
        }
        val members =
            docs.mapNotNull { doc ->
                byDoc[doc]
                    .orEmpty()
                    .filter { it.state in SubmissionBoundary.SUBMITTED_STATES }
                    .maxByOrNull { it.version }
                    ?.let { SubmittedMember(doc, it.state) }
            }
        return CheckSubmissionBoundaryResult.Done(docs.size, members)
    }
}
