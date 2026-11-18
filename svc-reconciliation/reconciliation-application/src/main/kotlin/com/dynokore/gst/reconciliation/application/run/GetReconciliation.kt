package com.dynokore.gst.reconciliation.application.run

import com.dynokore.gst.reconciliation.domain.run.RunOutcome

/** Read port: is a return recorded for the period of the tenant. Reads one tenant only. */
fun interface ReturnRecorded {
    fun exists(
        tenant: String,
        entityRef: String,
        periodRef: String,
    ): Boolean
}

sealed interface ReconciliationView {
    /** The latest completed run of the period. */
    data class Found(
        val snapshot: RunSnapshot,
    ) : ReconciliationView

    /** A return is recorded and no run has completed yet. */
    data class Pending(
        val entityRef: String,
        val periodRef: String,
    ) : ReconciliationView

    /** No return is recorded: no comparison exists (INV-REQ-657). */
    data class NoReturn(
        val code: String = RunOutcome.PER_008,
    ) : ReconciliationView
}

/**
 * Answers the latest completed run, else pending when a return is recorded, else no-return.
 * Port failures propagate unchanged.
 */
class GetReconciliation(
    private val runs: RunReader,
    private val returns: ReturnRecorded,
) {
    fun execute(
        tenant: String,
        entityRef: String,
        periodRef: String,
    ): ReconciliationView {
        val snapshot = runs.latest(tenant, entityRef, periodRef)
        if (snapshot != null) return ReconciliationView.Found(snapshot)
        return if (returns.exists(tenant, entityRef, periodRef)) {
            ReconciliationView.Pending(entityRef, periodRef)
        } else {
            ReconciliationView.NoReturn()
        }
    }
}
