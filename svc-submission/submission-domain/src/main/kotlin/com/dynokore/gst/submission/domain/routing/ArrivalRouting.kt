package com.dynokore.gst.submission.domain.routing

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.ordering.isTransmitted

/** A fact the arrival path may not know; UNKNOWN is never guessed into YES or NO. */
enum class Fact { YES, NO, UNKNOWN }

/** UNKNOWN is the class of a later version whose class has no stored source yet; it never rejects. */
enum class ChangeClass { NON_MONETARY, IDENTITY, PERIOD_SHIFTING, MONETARY, STRUCTURAL, UNKNOWN }

/** The inputs that were unknown to a routing decision, in declaration order. */
enum class UnresolvedInput { PRECEDING_PRE_OBLIGATION, CHANGE_CLASS, TYPE2_RENDITION_ISSUED }

/** [changeClass] is null exactly when [earlierVersions] is empty (an unknown class of a later version is UNKNOWN). */
data class ArrivalInput(
    val creditNote: Boolean,
    val periodFiled: Boolean,
    val precedingPreObligation: Fact,
    val earlierVersions: List<LifecycleState>,
    val changeClass: ChangeClass?,
    val type2RenditionIssued: Fact,
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
    val unresolved: List<UnresolvedInput>,
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
        val standalone = input.creditNote && input.precedingPreObligation != Fact.NO
        val amendment = amendmentOf(input.earlierVersions)
        val codes =
            buildList {
                if (input.periodFiled) add(ArrivalCode.SUB_010)
                if (standalone) add(ArrivalCode.PER_007)
            }
        val warn = warnsType2(input, amendment)
        val unresolved = unresolvedOf(input, amendment)
        return Routed(
            timing = if (input.periodFiled) Timing.IMMEDIATE else Timing.ROUTINE,
            ordering = if (standalone) Ordering.STANDALONE else Ordering.NORMAL,
            amendment = amendment,
            codes = codes,
            warnings = if (warn) listOf(ArrivalWarning.TYPE2_RENDITION_ISSUED) else emptyList(),
            unresolved = unresolved,
        )
    }

    private fun amendmentOf(earlier: List<LifecycleState>): Amendment =
        when {
            earlier.isEmpty() -> Amendment.NONE
            earlier.any(::isTransmitted) -> Amendment.AFTER_TRANSMISSION
            else -> Amendment.SUPERSEDE
        }

    private fun type2Relevant(
        input: ArrivalInput,
        amendment: Amendment,
    ): Boolean =
        amendment == Amendment.SUPERSEDE &&
            (input.changeClass == ChangeClass.MONETARY || input.changeClass == ChangeClass.UNKNOWN)

    private fun warnsType2(
        input: ArrivalInput,
        amendment: Amendment,
    ): Boolean = type2Relevant(input, amendment) && input.type2RenditionIssued != Fact.NO

    private fun unresolvedOf(
        input: ArrivalInput,
        amendment: Amendment,
    ): List<UnresolvedInput> =
        buildList {
            if (input.creditNote && input.precedingPreObligation == Fact.UNKNOWN) {
                add(UnresolvedInput.PRECEDING_PRE_OBLIGATION)
            }
            if (input.changeClass == ChangeClass.UNKNOWN) add(UnresolvedInput.CHANGE_CLASS)
            if (type2Relevant(input, amendment) && input.type2RenditionIssued == Fact.UNKNOWN) {
                add(UnresolvedInput.TYPE2_RENDITION_ISSUED)
            }
        }
}
