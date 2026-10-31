package com.dynokore.gst.controlPlane.domain.release

/**
 * The closed set of release gates (INV-REQ-2657): internal quality checks only. Accreditation currency, a changed
 * external rule and an open question are not in it, so no code path can name one as the reason a release is held.
 */
enum class Gate { SCENARIOS, REGENERATION, SECURITY, CERTIFICATION, CORPUS, STAGING }

enum class GateOutcome { PASSED, FAILED }

/**
 * One gate run against the candidate and, for a failing gate, re-run against the released version (INV-REQ-2657 b4).
 * [findings] name what failed (a finding id, a golden document, a document whose classification changed); the
 * baseline's findings are what the released version shows.
 */
data class GateRun(
    val gate: Gate,
    val candidate: GateOutcome,
    val findings: Set<String> = emptySet(),
    val baseline: GateOutcome? = null,
    val baselineFindings: Set<String> = emptySet(),
) {
    /** What the candidate shows that the released version does not. */
    val introduced: Set<String> get() = findings - baselineFindings
}

/** Why one gate holds a release back. */
data class GateBlock(
    val gate: Gate,
    val reason: Reason,
    val introduced: Set<String>,
) {
    enum class Reason { NO_RUN, FAILED, INTRODUCED, NO_BASELINE }
}

/** The outcome of the gates: [blocks] is empty exactly when the release may go ahead. */
data class GateVerdict(
    val runs: List<GateRun>,
    val exposureReducing: Boolean,
    val blocks: List<GateBlock>,
) {
    val releasable: Boolean get() = blocks.isEmpty()
}

/**
 * The release gates (INV-REQ-2657), deny by default: a required gate with no run holds the release back. A failed gate
 * holds it back, except for a release that only reduces exposure: that one is held back only by a failure it
 * introduced, that is a failing gate whose baseline run (the released version) passes or whose findings the released
 * version does not show. A failed gate with no baseline run holds an exposure-reducing release back too.
 */
object ReleaseGates {
    fun evaluate(
        required: Set<Gate>,
        runs: List<GateRun>,
        exposureReducing: Boolean,
    ): GateVerdict {
        val byGate = runs.groupBy { it.gate }
        val blocks =
            Gate.entries.filter { it in required }.mapNotNull { gate ->
                val own = byGate[gate].orEmpty()
                when {
                    own.size != 1 -> GateBlock(gate, GateBlock.Reason.NO_RUN, emptySet())
                    else -> blockOf(own.single(), exposureReducing)
                }
            }
        return GateVerdict(runs, exposureReducing, blocks)
    }

    private fun blockOf(
        run: GateRun,
        exposureReducing: Boolean,
    ): GateBlock? =
        when {
            run.candidate == GateOutcome.PASSED -> {
                null
            }

            !exposureReducing -> {
                GateBlock(run.gate, GateBlock.Reason.FAILED, run.introduced)
            }

            run.baseline == null -> {
                GateBlock(run.gate, GateBlock.Reason.NO_BASELINE, run.introduced)
            }

            run.baseline == GateOutcome.PASSED || run.introduced.isNotEmpty() -> {
                GateBlock(run.gate, GateBlock.Reason.INTRODUCED, run.introduced)
            }

            else -> {
                null
            }
        }
}
