package com.dynokore.platform.rulepackkernel.evaluation

import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class EvaluatorConflictTest {
    private val scope = PackType("Scope")
    private val applied = listOf(AppliedPack(scope, PackId("scope-pack"), PackVersionLabel("2026.3")))
    private val inputs = EvaluationInputs.of(mapOf("k" to "v"))

    private class FixedRule(
        override val ruleId: String,
        private val verdict: String?,
        private val terminal: Boolean = false,
        override val packType: PackType = PackType("Scope"),
    ) : Rule {
        override fun evaluate(inputs: EvaluationInputs): RuleResult? = verdict?.let { RuleResult(it, terminal) }
    }

    private fun run(vararg rules: Rule) = Evaluator(rules.toList()).evaluate(applied, inputs)

    @Test
    fun `INV-AC-19147 two rules that fire with different verdicts give Conflict with both rule ids and no verdict`() {
        val outcome = run(FixedRule("R-1", "A"), FixedRule("R-2", "B"))
        assertEquals(EvaluationOutcome.Conflict(listOf("R-1", "R-2")), outcome)
        assertNotEquals(EvaluationOutcome.Decided::class, outcome::class)
    }

    @Test
    fun `INV-AC-19147 verdicts that differ only in case or by a trailing space are different verdicts`() {
        listOf("in_scope", "IN_SCOPE ").forEach {
            assertEquals(
                EvaluationOutcome.Conflict(listOf("R-1", "R-2")),
                run(FixedRule("R-1", "IN_SCOPE"), FixedRule("R-2", it)),
                it,
            )
        }
    }

    @Test
    fun `INV-AC-19147 three rules, two agree and one differs, give Conflict naming all three ids ascending`() {
        assertEquals(
            EvaluationOutcome.Conflict(listOf("R-1", "R-2", "R-3")),
            run(FixedRule("R-3", "A"), FixedRule("R-1", "B"), FixedRule("R-2", "A")),
        )
    }

    @Test
    fun `INV-AC-19147 a rule that does not fire or has no applied pack is not named in a Conflict`() {
        val silent = run(FixedRule("R-1", "A"), FixedRule("R-2", "A"), FixedRule("R-3", null))
        assertEquals("A", assertInstanceOf(EvaluationOutcome.Decided::class.java, silent).evaluation.verdict)
        val unpacked = run(FixedRule("R-1", "A"), FixedRule("R-2", "B", packType = PackType("Other")))
        assertEquals("A", assertInstanceOf(EvaluationOutcome.Decided::class.java, unpacked).evaluation.verdict)
    }

    @Test
    fun `INV-AC-19147 rules that fire with one verdict string give Decided with that verdict and both trace steps`() {
        val outcome = run(FixedRule("R-1", "A"), FixedRule("R-2", "A"))
        val evaluation = assertInstanceOf(EvaluationOutcome.Decided::class.java, outcome).evaluation
        assertEquals("A", evaluation.verdict)
        assertEquals(listOf("R-1", "R-2"), evaluation.trace.steps.map { it.ruleId })
    }

    @Test
    fun `INV-AC-19147 the same Conflict twice, in a fresh instance and with rules reversed is equal`() {
        val rules = listOf(FixedRule("R-1", "A"), FixedRule("R-2", "B"), FixedRule("R-3", "A"))
        val evaluator = Evaluator(rules)
        val first = evaluator.evaluate(applied, inputs)
        assertEquals(first, evaluator.evaluate(applied, inputs))
        assertEquals(first, Evaluator(rules.toList()).evaluate(applied, inputs))
        assertEquals(first, Evaluator(rules.reversed()).evaluate(applied, inputs))
        assertEquals(EvaluationOutcome.Conflict(listOf("R-1", "R-2", "R-3")), first)
    }

    @Test
    fun `INV-AC-19147 a fired terminal rule ends the run at its list position`() {
        val ended = run(FixedRule("R-T", "A", terminal = true), FixedRule("R-1", "B"))
        val evaluation = assertInstanceOf(EvaluationOutcome.Decided::class.java, ended).evaluation
        assertEquals("A", evaluation.verdict)
        assertEquals(1, evaluation.trace.steps.size)
        assertEquals(
            EvaluationOutcome.Conflict(listOf("R-1", "R-T")),
            run(FixedRule("R-1", "B"), FixedRule("R-T", "A", terminal = true)),
        )
    }
}
