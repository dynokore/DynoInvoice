package com.dynokore.gst.submission.application.readiness

import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.readiness.CompletenessGate
import com.dynokore.gst.submission.domain.readiness.CompletenessState
import com.dynokore.gst.submission.domain.readiness.PeriodVerdict
import com.dynokore.gst.submission.domain.readiness.ReadinessCounter
import com.dynokore.gst.submission.domain.readiness.ReadinessEntry
import com.dynokore.gst.submission.domain.readiness.ReadinessResult
import java.time.Clock
import java.time.LocalDate

/** The register entries of a period; null means the register data for the period cannot be read. */
fun interface ReadinessEntrySource {
    fun entries(
        entityRef: String,
        periodRef: String,
    ): List<ReadinessEntry>?
}

/** The obligation start date and the completeness state of a period. */
data class ReadinessInputs(
    val obligationStartDate: LocalDate?,
    val completeness: CompletenessState,
)

fun interface ReadinessInputSource {
    fun inputs(
        entityRef: String,
        periodRef: String,
    ): ReadinessInputs
}

sealed interface ReadinessRunResult {
    data class Determined(
        val verdict: PeriodVerdict,
        val result: ReadinessResult,
        val check: StoredReadinessCheck,
    ) : ReadinessRunResult

    /** The data is unavailable: no verdict is shown and no check is stored (INV-REQ-974). */
    data object Undetermined : ReadinessRunResult
}

/**
 * Runs the period readiness check (INV-REQ-71): the counter verdict combined with the completeness gate, one stored
 * check per determined run, none when the data is unavailable. Appends in the caller's open tenant transaction.
 */
class RunReadiness(
    private val entries: ReadinessEntrySource,
    private val inputs: ReadinessInputSource,
    private val recorder: ReadinessCheckRecorder,
    private val clock: Clock,
) {
    fun run(
        entityRef: String,
        periodRef: String,
        approver: String,
    ): ReadinessRunResult {
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(periodRef.isNotBlank()) { "periodRef must not be blank" }
        require(approver.isNotBlank()) { "approver must not be blank" }

        val register = entries.entries(entityRef, periodRef) ?: return ReadinessRunResult.Undetermined
        val input = inputs.inputs(entityRef, periodRef)
        val counted = ReadinessCounter.evaluate(register, input.obligationStartDate, dataAvailable = true)
        val verdict = CompletenessGate.decide(counted, input.completeness)
        val checkVerdict =
            when (verdict) {
                PeriodVerdict.Ready -> CheckVerdict.READY
                is PeriodVerdict.NotReady -> CheckVerdict.NOT_READY
                PeriodVerdict.UnableToDetermine -> return ReadinessRunResult.Undetermined
            }
        val check = recorder.append(entityRef, periodRef, checkVerdict, clock.instant(), approver, null)
        return ReadinessRunResult.Determined(verdict, counted, check)
    }
}
