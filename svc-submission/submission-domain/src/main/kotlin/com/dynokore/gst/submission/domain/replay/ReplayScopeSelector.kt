package com.dynokore.gst.submission.domain.replay

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import java.time.Instant
import java.time.LocalDate

/** A version that a replay by scope may re-enqueue; [failureClass] is the catalogue code stored with it. */
data class ReplayCandidate(
    val docUuid: String,
    val version: Int,
    val entityRef: String,
    val periodRef: String,
    val state: LifecycleState,
    val failureClass: String?,
    val dueDate: LocalDate,
    val queuedAt: Instant,
) {
    init {
        require(docUuid.isNotBlank()) { "docUuid must not be blank" }
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(periodRef.isNotBlank()) { "periodRef must not be blank" }
        require(version >= 1) { "version must be at least 1" }
        require(failureClass == null || failureClass.isNotBlank()) { "failureClass must not be blank" }
    }
}

/** The entity is always named; every optional field narrows the scope and is never blank. */
data class ReplayScope(
    val entityRef: String,
    val periodRef: String?,
    val docUuid: String?,
    val failureClass: String?,
) {
    init {
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(periodRef == null || periodRef.isNotBlank()) { "periodRef must not be blank" }
        require(docUuid == null || docUuid.isNotBlank()) { "docUuid must not be blank" }
        require(failureClass == null || failureClass.isNotBlank()) { "failureClass must not be blank" }
    }
}

data class ReplaySelection(
    val selected: List<ReplayCandidate>,
    val truncated: Boolean,
)

/**
 * Selects the dead-lettered versions a replay by scope re-enqueues (INV-REQ-563 behavior 2,
 * INV-REQ-575 behavior 2). Pure: reads no clock, file or database and decides no access.
 */
object ReplayScopeSelector {
    private val ORDER: Comparator<ReplayCandidate> =
        compareBy<ReplayCandidate> { it.dueDate }
            .thenBy { it.queuedAt }
            .thenBy { it.docUuid }
            .thenBy { it.version }

    fun select(
        candidates: List<ReplayCandidate>,
        scope: ReplayScope,
        cap: Int,
    ): ReplaySelection {
        require(cap >= 1) { "cap must be at least 1" }
        val keys = HashSet<Pair<String, Int>>()
        for (c in candidates) {
            require(keys.add(c.docUuid to c.version)) { "duplicate candidate ${c.docUuid} v${c.version}" }
        }
        val matches = candidates.filter { matches(it, scope) }.sortedWith(ORDER)
        return ReplaySelection(matches.take(cap), matches.size > cap)
    }

    private fun matches(
        c: ReplayCandidate,
        scope: ReplayScope,
    ): Boolean =
        c.state == LifecycleState.DEAD_LETTERED &&
            c.entityRef == scope.entityRef &&
            (scope.periodRef == null || c.periodRef == scope.periodRef) &&
            (scope.docUuid == null || c.docUuid == scope.docUuid) &&
            (scope.failureClass == null || c.failureClass == scope.failureClass)
}
