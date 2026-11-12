package com.dynokore.gst.submission.application.replay

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId

/** The entity and state of one version, as stored. */
data class ReplayVersionView(
    val entityRef: String,
    val state: LifecycleState,
)

/** Reads the entity and state of one version of one tenant; null when the version does not exist for the tenant. */
fun interface ReplayVersionLookup {
    fun find(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): ReplayVersionView?
}

data class ReplaySingleVersionRequest(
    val tenant: TenantId,
    val principal: Principal,
    val docUuid: String,
    val version: Int,
)

sealed interface ReplaySingleVersionResult {
    data object Requeued : ReplaySingleVersionResult

    data object AlreadyQueued : ReplaySingleVersionResult

    data object InFlightConflict : ReplaySingleVersionResult

    data object NotFound : ReplaySingleVersionResult

    data class NotReplayable(
        val state: LifecycleState,
    ) : ReplaySingleVersionResult

    data class Forbidden(
        val reason: DecisionReason,
    ) : ReplaySingleVersionResult
}

/**
 * Replays one version (`replaySubmissions`, INV-REQ-575) behind [ReplayActions.REPLAY] for the entity of the version
 * itself. Order: lookup (unknown or foreign-tenant version is NotFound, no replica call), decision by the replica
 * alone and fail closed (no role name is read), state check, then the requeue port. Holds no clock, SQL or number.
 */
class ReplaySingleVersion(
    private val replica: AuthorityReplica,
    private val lookup: ReplayVersionLookup,
    private val requeue: ReplayRequeue,
) {
    fun handle(
        request: ReplaySingleVersionRequest,
        deadline: Deadline,
    ): ReplaySingleVersionResult {
        val view =
            lookup.find(request.tenant, request.docUuid, request.version)
                ?: return ReplaySingleVersionResult.NotFound
        val decision =
            replica.decide(
                AuthorizationRequest(request.tenant, request.principal, view.entityRef, ReplayActions.REPLAY),
                deadline,
            )
        return when {
            !decision.allowed || decision.reason == DecisionReason.AUTHORITY_UNAVAILABLE -> {
                ReplaySingleVersionResult.Forbidden(decision.reason)
            }

            view.state != LifecycleState.DEAD_LETTERED && view.state != LifecycleState.QUEUED -> {
                ReplaySingleVersionResult.NotReplayable(view.state)
            }

            else -> {
                requeued(request)
            }
        }
    }

    private fun requeued(request: ReplaySingleVersionRequest): ReplaySingleVersionResult =
        when (requeue.requeue(request.tenant, request.docUuid, request.version)) {
            RequeueOutcome.REQUEUED -> ReplaySingleVersionResult.Requeued
            RequeueOutcome.ALREADY_QUEUED -> ReplaySingleVersionResult.AlreadyQueued
            RequeueOutcome.IN_FLIGHT_CONFLICT -> ReplaySingleVersionResult.InFlightConflict
        }
}
