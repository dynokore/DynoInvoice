package com.dynokore.gst.submission.application.drain

import com.dynokore.gst.submission.application.queue.DespatchedVersion
import com.dynokore.gst.submission.application.readiness.ReadinessRunResult
import com.dynokore.gst.submission.application.readiness.RunReadiness
import com.dynokore.gst.submission.domain.drain.Tier
import com.dynokore.platform.outboxandqueue.DrainOrderAssigner
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Instant

/** A version of the drained entity and period that the drain could not despatch, with its stored reason code. */
data class ResidualItem(
    val docUuid: String,
    val version: Int,
    val tier: Tier,
    val reasonCode: String,
)

data class TierBlocked(
    val tier: Tier,
    val blockedCount: Int,
)

sealed interface DrainResult {
    data class Drained(
        val entityRef: String,
        val periodRef: String,
        val drainedCount: Int,
        val remaining: List<TierBlocked>,
        val completedAt: Instant,
        val drainedByTier: Map<Tier, Int>,
        val residualItems: List<ResidualItem>,
        val checkId: String?,
    ) : DrainResult

    data class Forbidden(
        val reason: DecisionReason,
    ) : DrainResult

    data class Unavailable(
        val cause: String,
    ) : DrainResult
}

/**
 * Drains the backlog of a tenant in three tiers and reports what cannot be drained (INV-REQ-554): authorize, select,
 * then per batch write tier and rank of the whole selection in one tenant transaction before despatching, and after
 * the loop read the residual items and run the readiness check in its own transaction. The acting user id is the
 * principal id, never a role.
 */
@Suppress("LongParameterList") // one port or collaborator per step of the drain
class DrainPeriod(
    private val authority: DrainAuthority,
    private val selection: DrainSelection,
    private val assigner: DrainOrderAssigner,
    private val despatch: NextDespatch,
    private val residuals: ResidualSource,
    private val readiness: RunReadiness,
    private val tenants: TenantContext,
    private val transactions: TransactionRunner,
    private val config: DrainConfig,
    private val clock: Clock,
) {
    fun run(
        request: DrainRequest,
        deadline: Deadline,
    ): DrainResult {
        check(tenants.current() == request.tenant) { "the request tenant is not the bound tenant" }
        val denied = denial(authority, request, ::drainKey, deadline)
        return if (denied != null) {
            DrainResult.Forbidden(denied)
        } else {
            drain(request)
        }
    }

    @Suppress("ReturnCount")
    private fun drain(request: DrainRequest): DrainResult {
        var selected = selection.select(request)
        if (selected !is DrainSelected.Ranked) return DrainResult.Unavailable(REFUSED)
        val counts = Tier.entries.associateWith { 0 }.toMutableMap()
        val done = HashSet<DespatchedVersion>()
        var open = true
        while (open && selected is DrainSelected.Ranked && selected.items.isNotEmpty()) {
            val tiers = selected.items.associate { (it.item.docUuid to it.item.version) to it.tier }
            if (!writeOrder(selected)) return DrainResult.Unavailable(ORDER_FAILED)
            open = despatchBatch(done, tiers, counts)
            selected = if (open) selection.select(request) else selected
        }
        if (selected is DrainSelected.Refused) return DrainResult.Unavailable(REFUSED)
        return report(request, counts, done.size)
    }

    /** One tenant transaction: a failure rolls every write back and nothing of the batch is despatched. */
    private fun writeOrder(selected: DrainSelected.Ranked): Boolean =
        try {
            transactions.inTransaction {
                selected.items.forEachIndexed { index, ranked ->
                    val tier = ranked.tier
                    val lane = if (tier == Tier.WINDOW_OPEN) Lane.DEADLINE_NEAR else null
                    assigner.assignDrainOrder(ranked.item.docUuid, tier.number, index + 1, lane)
                }
            }
            true
        } catch (_: RuntimeException) {
            false
        }

    /** @return false when the loop ends: nothing claimable, or a version of this call came back. */
    @Suppress("ReturnCount")
    private fun despatchBatch(
        done: MutableSet<DespatchedVersion>,
        tiers: Map<Pair<String, Int>, Tier>,
        counts: MutableMap<Tier, Int>,
    ): Boolean {
        repeat(config.batchSize) {
            val next = despatch.next() ?: return false
            if (!done.add(next)) return false
            val tier = tiers[next.docUuid to next.version] ?: Tier.ROUTINE
            counts[tier] = counts.getValue(tier) + 1
        }
        return true
    }

    @Suppress("ReturnCount")
    private fun report(
        request: DrainRequest,
        counts: Map<Tier, Int>,
        drained: Int,
    ): DrainResult {
        val residual = residualItems(request) ?: return DrainResult.Unavailable(RESIDUAL_UNREADABLE)
        val outcome =
            try {
                transactions.inTransaction { readiness.run(request.entityRef, request.periodRef, request.principal.id) }
            } catch (_: RuntimeException) {
                return DrainResult.Unavailable(READINESS_FAILED)
            }
        val checkId = (outcome as? ReadinessRunResult.Determined)?.check?.checkId
        val remaining =
            Tier.entries
                .map { tier -> TierBlocked(tier, residual.count { it.tier == tier }) }
                .filter { it.blockedCount > 0 }
        return DrainResult.Drained(
            request.entityRef,
            request.periodRef,
            drained,
            remaining,
            clock.instant(),
            counts.toMap(),
            residual,
            checkId,
        )
    }

    /** Null when the candidates, a code or the data for a tier cannot be trusted: no code is invented or defaulted. */
    @Suppress("ReturnCount")
    private fun residualItems(request: DrainRequest): List<ResidualItem>? {
        val read = residuals.candidates(request.tenant, request.entityRef, request.periodRef) ?: return null
        val items = ArrayList<ResidualItem>()
        for (candidate in read) {
            val code = candidate.reasonCode
            if (code == null && candidate.kind == ResidualKind.PROVISIONAL) continue
            if (code == null || !CODE.matches(code)) return null
            val tier =
                selection.tierOfDrained(request, candidate.docUuid, candidate.version, candidate.recordedAt)
                    ?: return null
            items += ResidualItem(candidate.docUuid, candidate.version, tier, code)
        }
        return items
    }

    private companion object {
        val CODE = Regex("^[A-Z]{3}-[0-9]{3}$")
        const val REFUSED = "a due date, a margin or the backlog is unavailable"
        const val ORDER_FAILED = "the drain order could not be written"
        const val RESIDUAL_UNREADABLE = "the residual items cannot be read"
        const val READINESS_FAILED = "the readiness check could not be recorded"
    }
}
