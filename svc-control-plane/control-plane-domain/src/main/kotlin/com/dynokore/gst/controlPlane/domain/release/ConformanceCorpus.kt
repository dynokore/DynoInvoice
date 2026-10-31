package com.dynokore.gst.controlPlane.domain.release

/** The five parts of the rule conformance corpus (INV-REQ-2660 b1). */
enum class CorpusSection { WORKED_EXAMPLES, SCENARIO_MATRIX, SCOPE_MATRIX, CODE_MATRIX, EDGE_CORPUS }

/** A golden document: its known-correct classification (the MDE population) under any correct pack. */
data class GoldenDocument(
    val id: DocumentId,
    val section: CorpusSection,
    val expected: String,
)

/**
 * The conformance run of one pack (INV-REQ-2660): [failing] are the golden documents the pack classifies other than
 * expected. The corpus is full only when every section holds a document; a partial corpus is not a run.
 */
data class CorpusRun(
    val failing: Set<DocumentId>,
    val sections: Set<CorpusSection>,
) {
    val complete: Boolean get() = sections.containsAll(CorpusSection.entries)
}

object ConformanceRun {
    fun run(
        corpus: List<GoldenDocument>,
        classify: (GoldenDocument) -> String?,
    ): CorpusRun =
        CorpusRun(
            corpus.filterTo(LinkedHashSet()) { classify(it) != it.expected }.mapTo(LinkedHashSet()) { it.id },
            corpus.mapTo(LinkedHashSet()) { it.section },
        )

    /**
     * The CORPUS gate run of a candidate pack, with the released predecessor's run as the baseline (INV-AC-2674): a
     * rule-correction pack is judged on the golden documents it newly breaks.
     */
    fun gateRun(
        candidate: CorpusRun,
        released: CorpusRun?,
    ): GateRun =
        GateRun(
            Gate.CORPUS,
            if (candidate.failing.isEmpty()) GateOutcome.PASSED else GateOutcome.FAILED,
            candidate.failing.mapTo(LinkedHashSet()) { it.value },
            released?.let { if (it.failing.isEmpty()) GateOutcome.PASSED else GateOutcome.FAILED },
            released?.failing?.mapTo(LinkedHashSet()) { it.value }.orEmpty(),
        )
}
