package com.dynokore.platform.rulepackkernel.trace

import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackVersionLabel

/**
 * The reference an error record carries to its trace (INV-REQ-1182 behaviour #2): `evidence-svc` is the
 * system of record (INV-ADR-282), so the record holds this reference, never a copy of the trace.
 */
@JvmInline
value class TraceReference(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "TraceReference must not be blank" }
    }

    override fun toString(): String = value
}

/** One rule that fired: which rule, from which pack version, on which inputs, with which verdict (INV-REQ-777). */
data class TraceStep(
    val ruleId: String,
    val packId: PackId,
    val packVersion: PackVersionLabel,
    val inputs: Map<String, String>,
    val verdict: String,
) {
    init {
        require(ruleId.isNotBlank()) { "ruleId must not be blank" }
        require(verdict.isNotBlank()) { "verdict must not be blank" }
    }
}

/** The trace of one evaluation. Pure data: the same inputs and pack version give the same trace (INV-REQ-775). */
data class DecisionTrace(
    val steps: List<TraceStep>,
) {
    init {
        require(steps.isNotEmpty()) { "a decision trace explains a verdict with at least one rule" }
    }
}
