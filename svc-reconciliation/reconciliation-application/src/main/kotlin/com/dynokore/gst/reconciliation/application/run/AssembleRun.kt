package com.dynokore.gst.reconciliation.application.run

import com.dynokore.gst.reconciliation.domain.run.ReconciliationRunner
import com.dynokore.gst.reconciliation.domain.run.RunOutcome
import java.time.Clock

/**
 * Reads the three inputs of [ReconciliationRunner.run] through ports and records the completed run.
 * The return is read first: with none, no other port is called and nothing is recorded (INV-REQ-657).
 * Opens no transaction; the caller does. Every call makes a new run; one run per return version is the trigger's rule.
 */
class AssembleRun(
    private val returns: ReturnFiguresSource,
    private val entries: MappingEntriesSource,
    private val documents: RunDocumentsSource,
    private val runs: CompletedRuns,
    private val clock: Clock,
) {
    fun execute(
        tenant: String,
        entityRef: String,
        periodRef: String,
    ): AssembledRun {
        val figures =
            returns.current(tenant, entityRef, periodRef)
                ?: return AssembledRun.NoReturn(RunOutcome.PER_008)
        val outcome =
            ReconciliationRunner.run(
                figures,
                entries.entries(tenant, entityRef, periodRef),
                documents.load(tenant, entityRef, periodRef),
            )
        check(outcome is RunOutcome.Completed) { "a run over an existing return must complete, got $outcome" }
        val recorded = runs.record(tenant, entityRef, periodRef, outcome, clock.instant())
        return AssembledRun.Completed(recorded, outcome)
    }
}
