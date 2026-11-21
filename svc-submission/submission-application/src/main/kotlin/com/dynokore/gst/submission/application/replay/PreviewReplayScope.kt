package com.dynokore.gst.submission.application.replay

import com.dynokore.gst.submission.application.register.RegisterListFactsStore
import com.dynokore.gst.submission.domain.replay.ReplayScope
import com.dynokore.gst.submission.domain.replay.ReplayScopeSelector
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Reads, without locking, whether a version of the Doc UUID other than [version] is in flight (a state of
 * `InFlight.NAMES`). Read-only; the write path decides the same conflict under lock.
 */
fun interface ReplayInFlightProbe {
    fun hasOtherInFlight(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): Boolean
}

data class PreviewReplayScopeRequest(
    val tenant: TenantId,
    val principal: Principal,
    val scope: ReplayScope,
)

sealed interface PreviewReplayScopeResult {
    data class Previewed(
        val entityRef: String,
        val documentCount: Int,
        val totalAmount: BigDecimal,
    ) : PreviewReplayScopeResult

    data class Forbidden(
        val reason: DecisionReason,
    ) : PreviewReplayScopeResult

    /** An amount is unavailable: no count and no partial total. */
    data class Unavailable(
        val cause: String,
    ) : PreviewReplayScopeResult
}

/**
 * Counts the versions a replay by scope would re-enqueue, before its cap, and sums taxable plus GST amount
 * (INV-REQ-1012). Behind [ReplayActions.REPLAY], decided by the replica alone and fail closed, before any read.
 * Changes no state; holds no clock, SQL or role name.
 */
class PreviewReplayScope(
    private val replica: AuthorityReplica,
    private val source: ReplayCandidateSource,
    private val probe: ReplayInFlightProbe,
    private val facts: RegisterListFactsStore,
) {
    @Suppress("ReturnCount")
    fun run(
        request: PreviewReplayScopeRequest,
        deadline: Deadline,
    ): PreviewReplayScopeResult {
        val scope = request.scope
        val decision =
            replica.decide(
                AuthorizationRequest(request.tenant, request.principal, scope.entityRef, ReplayActions.REPLAY),
                deadline,
            )
        if (!decision.allowed || decision.reason == DecisionReason.AUTHORITY_UNAVAILABLE) {
            return PreviewReplayScopeResult.Forbidden(decision.reason)
        }
        val selected =
            ReplayScopeSelector
                .select(source.candidates(request.tenant, scope.entityRef), scope, Int.MAX_VALUE)
                .selected
                .filterNot { probe.hasOtherInFlight(request.tenant, it.docUuid, it.version) }
        var total = BigDecimal.ZERO
        for (c in selected) {
            val listed = facts.facts(request.tenant, c.docUuid) ?: return PreviewReplayScopeResult.Unavailable(AMOUNT)
            total = total.add(listed.taxableAmount).add(listed.gstAmount)
        }
        val scaled =
            try {
                total.setScale(SCALE, RoundingMode.UNNECESSARY)
            } catch (_: ArithmeticException) {
                return PreviewReplayScopeResult.Unavailable(AMOUNT)
            }
        return PreviewReplayScopeResult.Previewed(scope.entityRef, selected.size, scaled)
    }

    private companion object {
        const val SCALE = 2
        const val AMOUNT = "an amount is unavailable"
    }
}
