package com.dynokore.gst.submission.application.replay

import com.dynokore.gst.submission.domain.replay.ReplayCandidate
import com.dynokore.gst.submission.domain.replay.ReplayScope
import com.dynokore.gst.submission.domain.replay.ReplayScopeSelector
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId

/** The scoped permission key of a replay by scope (INV-REQ-575, INV-Q-10027). */
object ReplayActions {
    const val REPLAY = "submission.replay"
}

/** Reads the replay candidates of one entity of one tenant (the store adapter is a later task). */
fun interface ReplayCandidateSource {
    fun candidates(
        tenant: TenantId,
        entityRef: String,
    ): List<ReplayCandidate>
}

/** Returns one version to QUEUED, idempotent on (docUuid, version); creates no new version. */
fun interface ReplayRequeue {
    fun requeue(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): RequeueOutcome
}

enum class RequeueOutcome { REQUEUED, ALREADY_QUEUED, IN_FLIGHT_CONFLICT }

data class ReplayBySelectionRequest(
    val tenant: TenantId,
    val principal: Principal,
    val scope: ReplayScope,
    val cap: Int,
)

sealed interface ReplayBySelectionResult {
    data class Done(
        val replayed: List<ReplayCandidate>,
        val skipped: List<ReplayCandidate>,
        val matchedTruncated: Boolean,
    ) : ReplayBySelectionResult

    data class Forbidden(
        val reason: DecisionReason,
    ) : ReplayBySelectionResult
}

/**
 * Replays dead-lettered versions by scope behind [ReplayActions.REPLAY] (INV-REQ-563, INV-REQ-575). The permission
 * is decided by the replica alone, fail closed; no role name is read. Holds no clock, SQL or number.
 */
class ReplayBySelection(
    private val replica: AuthorityReplica,
    private val source: ReplayCandidateSource,
    private val requeue: ReplayRequeue,
) {
    fun handle(
        request: ReplayBySelectionRequest,
        deadline: Deadline,
    ): ReplayBySelectionResult {
        val scope = request.scope
        val decision =
            replica.decide(
                AuthorizationRequest(request.tenant, request.principal, scope.entityRef, ReplayActions.REPLAY),
                deadline,
            )
        if (!decision.allowed || decision.reason == DecisionReason.AUTHORITY_UNAVAILABLE) {
            return ReplayBySelectionResult.Forbidden(decision.reason)
        }
        val selection =
            ReplayScopeSelector.select(source.candidates(request.tenant, scope.entityRef), scope, request.cap)
        val replayed = ArrayList<ReplayCandidate>()
        val skipped = ArrayList<ReplayCandidate>()
        for (c in selection.selected) {
            when (requeue.requeue(request.tenant, c.docUuid, c.version)) {
                RequeueOutcome.REQUEUED, RequeueOutcome.ALREADY_QUEUED -> replayed.add(c)
                RequeueOutcome.IN_FLIGHT_CONFLICT -> skipped.add(c)
            }
        }
        return ReplayBySelectionResult.Done(replayed, skipped, selection.truncated)
    }
}
