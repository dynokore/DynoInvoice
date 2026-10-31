package com.dynokore.gst.submission.domain.readiness

import com.dynokore.gst.catalogue.Disposition
import com.dynokore.gst.catalogue.ErrorCode

/** The completeness control state of a period (INV-REQ-791 outcome); the controls themselves are a later slice. */
sealed interface CompletenessState {
    data object Passed : CompletenessState

    data class Failed(
        val controls: List<String>,
    ) : CompletenessState {
        init {
            require(controls.isNotEmpty()) { "controls must not be empty" }
            require(controls.none { it.isBlank() }) { "control identifiers must not be blank" }
            require(controls.toSet().size == controls.size) { "control identifiers must be distinct" }
        }
    }

    data object Unavailable : CompletenessState
}

/** Block codes of the period verdict; each code is read from the catalogue. */
enum class BlockCode(
    val code: String,
) {
    PER_001(ErrorCode.PER_001.code),
    PER_002(ErrorCode.PER_002.code),
}

sealed interface PeriodVerdict {
    data object Ready : PeriodVerdict

    data class NotReady(
        val codes: Set<BlockCode>,
        val outstanding: Int,
        val failedControls: List<String>,
    ) : PeriodVerdict

    data object UnableToDetermine : PeriodVerdict
}

sealed interface OverrideOutcome {
    data object Allowed : OverrideOutcome

    data class Refused(
        val code: BlockCode,
        val disposition: Disposition,
    ) : OverrideOutcome

    data object NothingToOverride : OverrideOutcome
}

/**
 * A failed completeness control blocks the period verdict and cannot be overridden (INV-REQ-792, INV-AC-1848).
 * Pure: no clock, no state. Whether a code may be overridden is the catalogue disposition, never a literal here.
 */
object CompletenessGate {
    fun decide(
        readiness: ReadinessResult,
        completeness: CompletenessState,
    ): PeriodVerdict {
        val blocked = readiness as? ReadinessResult.Blocked
        val failed = completeness as? CompletenessState.Failed
        val codes =
            buildSet {
                if (blocked != null) add(BlockCode.PER_001)
                if (failed != null) add(BlockCode.PER_002)
            }
        return when {
            readiness is ReadinessResult.UnableToDetermine || completeness is CompletenessState.Unavailable ->
                PeriodVerdict.UnableToDetermine
            codes.isEmpty() -> PeriodVerdict.Ready
            else -> PeriodVerdict.NotReady(codes, blocked?.count ?: 0, failed?.controls.orEmpty())
        }
    }

    fun override(
        verdict: PeriodVerdict,
        code: BlockCode,
    ): OverrideOutcome {
        if (verdict !is PeriodVerdict.NotReady || code !in verdict.codes) return OverrideOutcome.NothingToOverride
        val disposition = ErrorCode.fromCode(code.code).disposition
        return if (disposition == Disposition.OVERRIDABLE) {
            OverrideOutcome.Allowed
        } else {
            OverrideOutcome.Refused(code, disposition)
        }
    }
}
