package com.dynokore.gst.reconciliation.application.run

import java.math.BigDecimal
import java.time.Instant

data class RunBox(
    val box: String,
    val submittedAmount: BigDecimal,
    val filedAmount: BigDecimal,
    val variance: BigDecimal,
    val varianceClass: String?,
    val unexplainedResidual: BigDecimal,
)

data class RunUnmappedLine(
    val docUuid: String,
    val version: Int,
    val lineNumber: Int,
    val taxCode: String,
)

data class RunSnapshot(
    val run: RecordedRun,
    val returnForm: String,
    val boxes: List<RunBox>,
    val unexplainedResidual: BigDecimal,
    val unmappedLines: List<RunUnmappedLine>,
    val raisesPer003: Boolean,
)

/** Keyset position: the last row of the previous page. */
data class RunCursor(
    val completedAt: Instant,
    val runId: String,
)

/** Read side of the recorded runs. Every method reads one tenant only. */
interface RunReader {
    fun latest(
        tenant: String,
        entityRef: String,
        periodRef: String,
    ): RunSnapshot?

    /** At most [limit] rows ordered by completedAt descending, then runId descending, strictly after [after]. */
    fun page(
        tenant: String,
        entityRef: String,
        periodRef: String,
        limit: Int,
        after: RunCursor?,
    ): List<RecordedRun>

    fun find(
        tenant: String,
        runId: String,
    ): RunSnapshot?
}
