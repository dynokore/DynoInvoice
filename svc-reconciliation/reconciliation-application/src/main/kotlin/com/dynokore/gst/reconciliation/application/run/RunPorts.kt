package com.dynokore.gst.reconciliation.application.run

import com.dynokore.gst.reconciliation.domain.boxes.MappingEntry
import com.dynokore.gst.reconciliation.domain.run.ReconcilingItem
import com.dynokore.gst.reconciliation.domain.run.ReturnFigures
import com.dynokore.gst.reconciliation.domain.run.RunDocument
import com.dynokore.gst.reconciliation.domain.run.RunOutcome
import java.time.Instant

/** The current (highest recorded) return of the period, or null when none was uploaded (INV-REQ-657). */
fun interface ReturnFiguresSource {
    fun current(
        tenant: String,
        entityRef: String,
        periodRef: String,
    ): ReturnFigures?
}

fun interface MappingEntriesSource {
    fun entries(
        tenant: String,
        entityRef: String,
        periodRef: String,
    ): List<MappingEntry>
}

fun interface RunDocumentsSource {
    fun load(
        tenant: String,
        entityRef: String,
        periodRef: String,
    ): List<RunDocument>
}

data class RecordedRun(
    val runId: String,
    val entityRef: String,
    val periodRef: String,
    val returnVersion: Int,
    val completedAt: Instant,
    val returnForm: String,
)

/** Records one completed run; a failed or NoReturn outcome is never offered to it. */
fun interface CompletedRuns {
    fun record(
        tenant: String,
        entityRef: String,
        periodRef: String,
        outcome: RunOutcome.Completed,
        completedAt: Instant,
    ): RecordedRun
}

sealed interface AssembledRun {
    /** The period has no return: nothing was compared and nothing was recorded. */
    data class NoReturn(
        val code: String,
    ) : AssembledRun

    data class Completed(
        val run: RecordedRun,
        val outcome: RunOutcome.Completed,
    ) : AssembledRun
}

/** The stored reconciling items of a run, ordered by box, docUuid, version, lineNumber (INV-REQ-982). */
fun interface RunItems {
    fun items(
        tenant: String,
        runId: String,
    ): List<ReconcilingItem>
}
