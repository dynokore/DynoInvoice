package com.dynokore.platform.rulepackkernel.evaluation

import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersionLabel
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

/**
 * What a rule that fired decided. A [terminal] result ends the evaluation (INV-REQ-775: no hidden state between
 * rules).
 */
data class RuleResult(
    val verdict: String,
    val terminal: Boolean = false,
)

/**
 * One rule of a service's own code reading the data of a pack (INV-REQ-775 behaviour 3: logic embedded in a pack is
 * never executed). [evaluate] sees only the explicit [inputs] and returns null when the rule does not fire; it reads
 * no clock, no store and no other service.
 */
interface Rule {
    val ruleId: String
    val packType: PackType

    fun evaluate(inputs: EvaluationInputs): RuleResult?
}

/** One pack version an evaluation applied; the caller records it on the document version (INV-REQ-771). */
data class AppliedPack(
    val packType: PackType,
    val packId: PackId,
    val version: PackVersionLabel,
)

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

    /**
     * Fired rules disagree (INV-Q-19135 B): at least two different verdict strings and no terminal rule ended the run
     * first. There is no verdict and no trace. [ruleIds] holds the id of every fired rule, ascending, so the outcome is
     * equal in any list order.
     */
    data class Conflict(
        val ruleIds: List<String>,
    ) : EvaluationOutcome
}

/**
 * Pure evaluation (INV-REQ-775): the same [inputs] and the same applied pack versions always give the same outcome.
 * The rules run in list order; each rule whose pack type has an applied version and that fires adds a trace step
 * naming the rule, that pack version, the inputs and the verdict. A rule of a pack type with no applied version is
 * skipped. A fired terminal rule ends the run at its list position. Fired rules that agree give
 * [EvaluationOutcome.Decided]; different verdict strings (compared by equality) give [EvaluationOutcome.Conflict],
 * in any list order.
 */
class Evaluator(
    private val rules: List<Rule>,
) {
    fun evaluate(
        applied: List<AppliedPack>,
        inputs: EvaluationInputs,
    ): EvaluationOutcome {
        val steps = mutableListOf<TraceStep>()
        val fired = mutableListOf<Pair<String, String>>()
        val pending = rules.iterator()
        var ended = false
        while (!ended && pending.hasNext()) {
            val rule = pending.next()
            val pack = applied.firstOrNull { it.packType == rule.packType }
            val result = if (pack == null) null else rule.evaluate(inputs)
            if (pack != null && result != null) {
                steps += TraceStep(rule.ruleId, pack.packId, pack.version, inputs.values, result.verdict)
                fired += rule.ruleId to result.verdict
                ended = result.terminal
            }
        }
        val verdicts = fired.map { it.second }.toSet()
        return when {
            fired.isEmpty() -> {
                EvaluationOutcome.NoRuleFired
            }

            verdicts.size > 1 -> {
                EvaluationOutcome.Conflict(fired.map { it.first }.sorted())
            }

            else -> {
                EvaluationOutcome.Decided(
                    Evaluation(verdicts.single(), DecisionTrace(steps.toList()), applied.toList()),
                )
            }
        }
    }
}
