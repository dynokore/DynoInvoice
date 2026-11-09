package com.dynokore.platform.rulepackkernel.evaluation

import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EvaluatorPurityTest {
    private val type = PackType("Scope")
    private val applied = listOf(AppliedPack(type, PackId("scope-pack"), PackVersionLabel("2026.3")))

    private class CodeRule(
        override val ruleId: String,
        private val code: String,
        private val verdict: String,
    ) : Rule {
        override val packType: PackType = PackType("Scope")

        override fun evaluate(inputs: EvaluationInputs): RuleResult? =
            if (inputs.values["code"] == code) RuleResult(verdict, terminal = true) else null
    }

    private fun rules() = listOf(CodeRule("R-1", "SR", "IN_SCOPE"), CodeRule("R-2", "ZR", "ZERO_RATED"))

    private val sample = mapOf("code" to "ZR", "b" to "2", "a" to "1", "c" to "3")

    @Test
    fun `INV-AC-9791 the same input evaluated twice and in a fresh instance gives equal outcomes`() {
        val evaluator = Evaluator(rules())
        val first = evaluator.evaluate(applied, EvaluationInputs.of(sample))
        assertEquals(first, evaluator.evaluate(applied, EvaluationInputs.of(sample)))
        assertEquals(first, Evaluator(rules()).evaluate(applied, EvaluationInputs.of(sample)))
    }

    @Test
    fun `INV-AC-9791 rules that cannot both fire give the same verdict and trace in either list order`() {
        val forward = Evaluator(rules()).evaluate(applied, EvaluationInputs.of(sample))
        val backward = Evaluator(rules().reversed()).evaluate(applied, EvaluationInputs.of(sample))
        assertEquals(forward, backward)
    }

    @Test
    fun `INV-AC-9791 the input map order does not change the verdict or the trace`() {
        val shuffled = linkedMapOf("c" to "3", "a" to "1", "code" to "ZR", "b" to "2")
        val keys =
            EvaluationInputs
                .of(shuffled)
                .values.keys
                .toList()
        assertEquals(keys.sorted(), keys)
        assertEquals(
            Evaluator(rules()).evaluate(applied, EvaluationInputs.of(sample)),
            Evaluator(rules()).evaluate(applied, EvaluationInputs.of(shuffled)),
        )
    }

    private class FixedRule(
        override val ruleId: String,
        private val verdict: String,
    ) : Rule {
        override val packType: PackType = PackType("Scope")

        override fun evaluate(inputs: EvaluationInputs): RuleResult = RuleResult(verdict)
    }

    @Test
    fun `INV-AC-9791 non-terminal rules that agree give the same verdict and trace steps in either order`() {
        val rules = listOf(FixedRule("R-1", "A"), FixedRule("R-2", "A"))
        val forward = Evaluator(rules).evaluate(applied, EvaluationInputs.of(sample))
        val backward = Evaluator(rules.reversed()).evaluate(applied, EvaluationInputs.of(sample))
        val a = (forward as EvaluationOutcome.Decided).evaluation
        val b = (backward as EvaluationOutcome.Decided).evaluation
        assertEquals(a.verdict, b.verdict)
        assertEquals(a.trace.steps.toSet(), b.trace.steps.toSet())
    }

    @Test
    fun `INV-AC-9791 non-terminal rules that disagree give Conflict with the same rule ids in either order`() {
        val rules = listOf(FixedRule("R-1", "A"), FixedRule("R-2", "B"))
        assertEquals(
            EvaluationOutcome.Conflict(listOf("R-1", "R-2")),
            Evaluator(rules).evaluate(applied, EvaluationInputs.of(sample)),
        )
        assertEquals(
            EvaluationOutcome.Conflict(listOf("R-1", "R-2")),
            Evaluator(rules.reversed()).evaluate(applied, EvaluationInputs.of(sample)),
        )
    }
}
