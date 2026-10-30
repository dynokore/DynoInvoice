package com.dynokore.gst.submission.domain.routing

import com.dynokore.gst.submission.domain.ordering.LifecycleState

enum class ChangeClass { NON_MONETARY, IDENTITY, PERIOD_SHIFTING, MONETARY, STRUCTURAL }

/** [changeClass] is null exactly when [earlierVersions] is empty. */
data class ArrivalInput(
    val creditNote: Boolean,
    val periodFiled: Boolean,
    val precedingPreObligation: Boolean,
    val earlierVersions: List<LifecycleState>,
    val changeClass: ChangeClass?,
    val type2RenditionIssued: Boolean,
)

enum class RejectionCode(
    val code: String,
) {
    ING_012("ING-012"),
}

enum class Timing { IMMEDIATE, ROUTINE }

enum class Ordering { STANDALONE, NORMAL }

enum class Amendment { NONE, SUPERSEDE, AFTER_TRANSMISSION }

enum class ArrivalCode(
    val code: String,
) {
    SUB_010("SUB-010"),
    PER_007("PER-007"),
}

enum class ArrivalWarning { TYPE2_RENDITION_ISSUED }

sealed interface ArrivalResult

data class Rejected(
    val code: RejectionCode,
) : ArrivalResult

data class Routed(
    val timing: Timing,
    val ordering: Ordering,
    val amendment: Amendment,
    val codes: List<ArrivalCode>,
    val warnings: List<ArrivalWarning>,
) : ArrivalResult

/**
 * INV-REQ-950, INV-REQ-338, INV-REQ-580 (INV-Q-6421, option A): the routing of an arriving version. Pure: no state, no
 * clock. The result holds nothing back: no code of it holds a version.
 */
object ArrivalRouting {
    fun decide(input: ArrivalInput): ArrivalResult {
        require((input.earlierVersions.isEmpty()) == (input.changeClass == null)) {
            "changeClass must be null exactly when earlierVersions is empty"
        }
        if (input.changeClass == ChangeClass.STRUCTURAL) {
            return Rejected(RejectionCode.ING_012)
        }
        val standalone = input.creditNote && input.precedingPreObligation
        val amendment = amendmentOf(input.earlierVersions)
        val codes =
            buildList {
                if (input.periodFiled) add(ArrivalCode.SUB_010)
                if (standalone) add(ArrivalCode.PER_007)
            }
        val warn =
            amendment == Amendment.SUPERSEDE && input.changeClass == ChangeClass.MONETARY && input.type2RenditionIssued
        return Routed(
            timing = if (input.periodFiled) Timing.IMMEDIATE else Timing.ROUTINE,
            ordering = if (standalone) Ordering.STANDALONE else Ordering.NORMAL,
            amendment = amendment,
            codes = codes,
            warnings = if (warn) listOf(ArrivalWarning.TYPE2_RENDITION_ISSUED) else emptyList(),
        )
    }

    private fun amendmentOf(earlier: List<LifecycleState>): Amendment =
        when {
            earlier.isEmpty() -> Amendment.NONE
            earlier.any(::transmitted) -> Amendment.AFTER_TRANSMISSION
            else -> Amendment.SUPERSEDE
        }

    /** Exhaustive over [LifecycleState]: a new member fails to compile here. */
    private fun transmitted(state: LifecycleState): Boolean =
        when (state) {
            LifecycleState.SENT,
            LifecycleState.SENT_UNCONFIRMED,
            LifecycleState.RETRY,
            LifecycleState.DEAD_LETTERED,
            LifecycleState.ACKNOWLEDGED,
            LifecycleState.SUPERSEDED,
            LifecycleState.NETWORK_ISSUED,
            LifecycleState.EXTERNALLY_SUBMITTED,
            -> true

            LifecycleState.READY,
            LifecycleState.AWAITING_PRECEDING,
            LifecycleState.QUEUED,
            LifecycleState.REJECTED,
            LifecycleState.CANCELLED,
            LifecycleState.WITHDRAWN,
            LifecycleState.INGESTED,
            LifecycleState.HELD,
            LifecycleState.BLOCKED,
            LifecycleState.NOT_SUBMITTABLE,
            -> false
        }
}
