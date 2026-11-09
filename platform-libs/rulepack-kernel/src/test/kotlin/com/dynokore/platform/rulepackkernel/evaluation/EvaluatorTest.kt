package com.dynokore.platform.rulepackkernel.evaluation

import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.rulepackkernel.trace.BusinessRule
import com.dynokore.platform.rulepackkernel.trace.BusinessTermCatalogue
import com.dynokore.platform.rulepackkernel.trace.BusinessTermRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** The assertions of rules-svc's EvaluationTest that exercise the moved types (INV-T-9785), unchanged in substance. */
class EvaluatorTest {
    private val scope = PackType("Scope")

    private object ScopeRule : Rule {
        override val ruleId = "R-114"
        override val packType: PackType = PackType("Scope")

        override fun evaluate(inputs: EvaluationInputs): RuleResult? =
            if (inputs.values["internalTaxCode"] == "SR-STD") RuleResult("IN_SCOPE", terminal = true) else null
    }

    private val inputs = EvaluationInputs.of(mapOf("internalTaxCode" to "SR-STD", "entityRef" to "E-001"))
    private val evaluator = Evaluator(listOf(ScopeRule))

    private fun applied(version: String) = listOf(AppliedPack(scope, PackId("scope-pack"), PackVersionLabel(version)))

    private fun decided(outcome: EvaluationOutcome) =
        assertInstanceOf(EvaluationOutcome.Decided::class.java, outcome).evaluation

    @Test
    fun `INV-AC-1728 repeated evaluation with the same inputs and pack version yields the same verdict and trace`() {
        val first = evaluator.evaluate(applied("2026.3"), inputs)
        val reordered = EvaluationInputs.of(mapOf("entityRef" to "E-001", "internalTaxCode" to "SR-STD"))
        val second = evaluator.evaluate(applied("2026.3"), reordered)
        assertEquals(first, second)
        assertEquals("IN_SCOPE", decided(first).verdict)
    }

    @Test
    fun `INV-AC-1730 the trace names the rule, the pack version, the inputs and the verdict in business terms`() {
        val trace = decided(evaluator.evaluate(applied("2026.3"), inputs)).trace
        val step = trace.steps.single()
        assertEquals("R-114", step.ruleId)
        assertEquals("2026.3", step.packVersion.value)
        assertEquals("SR-STD", step.inputs["internalTaxCode"])
        assertEquals("IN_SCOPE", step.verdict)
        val catalogue =
            BusinessTermCatalogue(
                rules =
                    mapOf(
                        "R-114" to
                            BusinessRule(
                                "Scope",
                                "Tax code {internalTaxCode} is checked.",
                                mapOf("IN_SCOPE" to "in scope"),
                            ),
                    ),
                inputLabels = mapOf("internalTaxCode" to "Internal tax code"),
            )
        val line = BusinessTermRenderer(catalogue).render(trace).lines.single()
        listOf("Scope", "SR-STD", "in scope", "2026.3").forEach { assertEquals(true, line.contains(it), it) }
    }

    @Test
    fun `no rule firing is no verdict and no trace`() {
        assertEquals(
            EvaluationOutcome.NoRuleFired,
            evaluator.evaluate(applied("2026.3"), EvaluationInputs.of(mapOf("internalTaxCode" to "ZR"))),
        )
    }

    @Test
    fun `a rule of a pack type with no applied version is skipped`() {
        assertEquals(EvaluationOutcome.NoRuleFired, evaluator.evaluate(emptyList(), inputs))
    }

    @Test
    fun `the applied pack versions are recorded in the evaluation`() {
        assertEquals(applied("2026.3"), decided(evaluator.evaluate(applied("2026.3"), inputs)).packVersionsApplied)
    }
}
