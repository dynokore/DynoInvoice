package com.dynokore.gst.submission.domain.backfill

import com.dynokore.gst.submission.domain.scope.NotSubmittableRouteReason
import com.dynokore.gst.submission.domain.scope.ScopeRoute
import com.dynokore.gst.submission.domain.scope.ScopeVerdictRouting

// Inference (INV-REQ-1090 behavior 1 states the counts, not the types): every name in this file is this task's own
// choice (INV-Q-13348 places the pure decisions in submission-domain).

/** One document a backfill found. */
data class BackfillCandidate(
    val docUuid: String,
    val periodRef: String,
    val scopeVerdict: String,
    val alreadySubmitted: Boolean,
)

/** The document-count effect of the backfill on one period; no amount-level effect (stated partial slice). */
data class PeriodEffect(
    val periodRef: String,
    val found: Int,
    val toBeSubmitted: Int,
)

/** What was found, what is in scope and what will be submitted (INV-REQ-1090 behavior 1, counts per period only). */
data class BackfillReconciliation(
    val found: Int,
    val duplicates: Int,
    val inScope: Int,
    val notSubmittable: Int,
    val preObligation: Int,
    val unrecognised: Int,
    val alreadySubmitted: Int,
    val toBeSubmitted: List<String>,
    val periods: List<PeriodEffect>,
)

/** Reconciles a backfill before submission. Pure: no state, no clock, no I/O; routing is [ScopeVerdictRouting]. */
object BackfillReconciler {
    fun reconcile(candidates: List<BackfillCandidate>): BackfillReconciliation {
        val (docs, duplicates) = distinct(candidates)
        val routes = docs.map { ScopeVerdictRouting.decide(it.scopeVerdict) }
        val sendable = docs.filterIndexed { i, d -> routes[i] == ScopeRoute.Submittable && !d.alreadySubmitted }
        val inScope = routes.count { it == ScopeRoute.Submittable }
        val toBeSubmitted = sendable.map { it.docUuid }.sorted()
        val sendableDocs = toBeSubmitted.toSet()
        val periods =
            docs
                .groupBy { it.periodRef }
                .toSortedMap()
                .map { (period, ds) -> PeriodEffect(period, ds.size, ds.count { it.docUuid in sendableDocs }) }
        return BackfillReconciliation(
            found = docs.size,
            duplicates = duplicates,
            inScope = inScope,
            notSubmittable =
                routes.count {
                    it is ScopeRoute.NotSubmittable && it.reason == NotSubmittableRouteReason.OUT_OF_SCOPE_OR_EXCLUDED
                },
            preObligation =
                routes.count {
                    it is ScopeRoute.NotSubmittable && it.reason == NotSubmittableRouteReason.PRE_OBLIGATION
                },
            unrecognised = routes.count { it is ScopeRoute.Unrecognised },
            alreadySubmitted = inScope - toBeSubmitted.size,
            toBeSubmitted = toBeSubmitted,
            periods = periods,
        )
    }

    private fun distinct(candidates: List<BackfillCandidate>): Pair<List<BackfillCandidate>, Int> {
        val byDoc = LinkedHashMap<String, BackfillCandidate>()
        var duplicates = 0
        for (c in candidates) {
            require(c.docUuid.isNotBlank()) { "docUuid must not be blank" }
            require(c.periodRef.isNotBlank()) { "periodRef must not be blank" }
            val first = byDoc.putIfAbsent(c.docUuid, c)
            if (first != null) {
                require(first == c) { "conflicting repeat of docUuid ${c.docUuid}" }
                duplicates++
            }
        }
        return byDoc.values.toList() to duplicates
    }
}
