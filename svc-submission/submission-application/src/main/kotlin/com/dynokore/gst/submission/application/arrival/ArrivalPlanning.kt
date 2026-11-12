package com.dynokore.gst.submission.application.arrival

import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.routing.ArrivalCode
import com.dynokore.gst.submission.domain.routing.ArrivalInput
import com.dynokore.gst.submission.domain.routing.ArrivalRouting
import com.dynokore.gst.submission.domain.routing.Fact
import com.dynokore.gst.submission.domain.routing.Routed
import com.dynokore.gst.submission.domain.routing.Timing
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.tenancykernel.TenantId

/**
 * Plans the arrival of a version (INV-Q-20897, INV-Q-20490): first versions are routed from the stored register
 * entry; facts with no stored source are carried as UNKNOWN and counted, never guessed. A later version, or a
 * document with no register entry, is enqueued routinely. Opens no transaction; holds no queue or store.
 */
class ArrivalPlanning(
    private val documents: RegisteredDocuments,
    private val filed: FiledPeriods,
    private val metrics: ArrivalMetrics,
) {
    fun plan(
        tenant: TenantId,
        docUuid: String,
        earlierStates: List<LifecycleState>,
    ): ArrivalPlan {
        if (earlierStates.isNotEmpty()) return routine()
        val entry = documents.find(tenant, docUuid)
        if (entry == null) metrics.unknownRegisterEntry()
        return if (entry == null) routine() else planFirst(entry)
    }

    private fun planFirst(entry: RegisteredDocument): ArrivalPlan {
        val input =
            ArrivalInput(
                creditNote = entry.kind == RowKind.CREDIT_NOTE,
                periodFiled = filed.isFiled(entry.entityRef, entry.periodRef),
                precedingPreObligation = Fact.UNKNOWN,
                earlierVersions = emptyList(),
                changeClass = null,
                type2RenditionIssued = Fact.UNKNOWN,
            )
        val routed = checkNotNull(ArrivalRouting.decide(input) as? Routed) { "Arrival of a first version was rejected" }
        routed.unresolved.forEach(metrics::unresolvedInput)
        return ArrivalPlan(
            lane = if (routed.timing == Timing.IMMEDIATE) Lane.DEADLINE_NEAR else Lane.LIVE,
            codes = routed.codes.filter { it in RAISED_CODES }.map { it.code },
            entityRef = entry.entityRef,
            periodRef = entry.periodRef,
        )
    }

    private fun routine() = ArrivalPlan(Lane.LIVE, emptyList(), null, null)

    companion object {
        /** Round zk: PER-007 stays unimplemented in this slice and is never raised. */
        val RAISED_CODES: Set<ArrivalCode> = setOf(ArrivalCode.SUB_010)
    }
}
