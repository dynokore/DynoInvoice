package com.dynokore.gst.rules.domain.evaluation

import com.dynokore.gst.rules.domain.resolution.AppliedPack
import com.dynokore.gst.rules.domain.resolution.PackBinding
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.trace.DecisionTrace
import com.dynokore.platform.rulepackkernel.trace.TraceStep

/** The explicit inputs of an evaluation, name to text, in a fixed order so equal inputs compare and trace equal. */
class EvaluationInputs private constructor(
    val values: Map<String, String>,
) {
    override fun equals(other: Any?): Boolean = other is EvaluationInputs && other.values == values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = values.toString()

    companion object {
        fun of(values: Map<String, String>): EvaluationInputs = EvaluationInputs(values.toSortedMap())
    }
}

/** What a rule that fired decided. A [terminal] result ends the evaluation (INV-REQ-775: no hidden state between rules). */
data class RuleResult(
    val verdict: String,
    val terminal: Boolean = false,
)

/**
 * One rule of rules-svc's own code reading the data of a pack (INV-REQ-775 behaviour 3: logic embedded in a pack is
 * never executed). [evaluate] sees only the explicit [inputs] and returns null when the rule does not fire; it reads
 * no clock, no store and no other service.
 */
interface Rule {
    val ruleId: String
    val packType: PackType

    fun evaluate(inputs: EvaluationInputs): RuleResult?
}

/** The verdict, the decision trace and the pack versions to record (INV-REQ-777, INV-REQ-771). */
data class Evaluation(
    val verdict: String,
    val trace: DecisionTrace,
    val packVersionsApplied: List<AppliedPack>,
)

sealed interface EvaluationOutcome {
    data class Decided(
        val evaluation: Evaluation,
    ) : EvaluationOutcome

    /** No rule fired: there is no verdict and so nothing to trace; the caller decides what that means. */
    data object NoRuleFired : EvaluationOutcome
}

/**
 * Pure evaluation (INV-REQ-775): the same [inputs] and the same [binding] always give the same outcome. Each rule that
 * fires adds a trace step naming the rule, the pack version of its pack type, the inputs and the verdict.
 */
class Evaluator(
    private val rules: List<Rule>,
) {
    fun evaluate(
        binding: PackBinding,
        inputs: EvaluationInputs,
    ): EvaluationOutcome {
        val steps = mutableListOf<TraceStep>()
        var verdict: String? = null
        for (rule in rules) {
            val applied = binding.appliedFor(rule.packType) ?: continue
            val result = rule.evaluate(inputs) ?: continue
            steps +=
                TraceStep(
                    ruleId = rule.ruleId,
                    packId = applied.packId,
                    packVersion = applied.version,
                    inputs = inputs.values,
                    verdict = result.verdict,
                )
            verdict = result.verdict
            if (result.terminal) break
        }
        val decided = verdict ?: return EvaluationOutcome.NoRuleFired
        return EvaluationOutcome.Decided(Evaluation(decided, DecisionTrace(steps.toList()), binding.applied))
    }
}

/**
 * An evaluation that has started (INV-REQ-1167): it holds the binding resolved at its start and completes under that
 * binding whatever is armed meanwhile. Only a new [InFlightEvaluation], made from a fresh resolution, sees a newer pack.
 */
class InFlightEvaluation(
    private val binding: PackBinding,
    private val inputs: EvaluationInputs,
) {
    fun complete(evaluator: Evaluator): EvaluationOutcome = evaluator.evaluate(binding, inputs)
}
