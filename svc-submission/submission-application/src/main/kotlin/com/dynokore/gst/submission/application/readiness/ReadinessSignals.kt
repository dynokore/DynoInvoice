package com.dynokore.gst.submission.application.readiness

import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.ordering.LifecycleState

/**
 * The readiness of one entity and period as the event carries it: the verdict, the unsubmitted count
 * (`blockedCount`, INV-REQ-570 behavior 4) and the document count of each lifecycle state that has at least one.
 */
data class ReadinessSnapshot(
    val verdict: CheckVerdict,
    val blockedCount: Int,
    val countsByState: Map<LifecycleState, Int>,
) {
    init {
        require(blockedCount >= 0) { "blockedCount must be at least 0" }
        require(countsByState.values.all { it >= 1 }) { "every state count must be at least 1" }
    }
}

/** What one `ReadinessChanged` signal needs to know: the subject, the new snapshot and the check that produced it. */
data class ReadinessChangedFacts(
    val entityRef: String,
    val periodRef: String,
    val snapshot: ReadinessSnapshot,
    val checkId: String,
) {
    init {
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(periodRef.isNotBlank()) { "periodRef must not be blank" }
        require(checkId.isNotBlank()) { "checkId must not be blank" }
    }
}

/** Stages the `ReadinessChanged` event in the caller's open tenant transaction (transactional outbox). */
fun interface ReadinessSignals {
    fun stage(facts: ReadinessChangedFacts)
}

/** The last signalled snapshot of each entity and period of the bound tenant. */
interface ReadinessSignalMemory {
    /**
     * Stores [snapshot] as the last signalled one of the bound tenant's [entityRef] and [periodRef] and answers true
     * when no snapshot was stored or the stored one differs in verdict, blocked count or counts by state, false when
     * it equals.
     */
    fun record(
        entityRef: String,
        periodRef: String,
        snapshot: ReadinessSnapshot,
    ): Boolean
}
