package com.dynokore.gst.rules.domain

import com.dynokore.gst.rules.domain.evaluation.Evaluator
import com.dynokore.gst.rules.domain.evaluation.EvaluationInputs
import com.dynokore.gst.rules.domain.evaluation.EvaluationOutcome
import com.dynokore.gst.rules.domain.evaluation.InFlightEvaluation
import com.dynokore.gst.rules.domain.evaluation.Rule
import com.dynokore.gst.rules.domain.evaluation.RuleResult
import com.dynokore.gst.rules.domain.resolution.PackResolution
import com.dynokore.gst.rules.domain.resolution.PackResolver
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.gst.rules.domain.resolution.ResolutionDates
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.trace.BusinessRule
import com.dynokore.platform.rulepackkernel.trace.BusinessTermCatalogue
import com.dynokore.platform.rulepackkernel.trace.BusinessTermRenderer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate

class EvaluationTest {
    private object ScopeRule : Rule {
        override val ruleId = "R-114"
        override val packType: PackType = PackTypes.SCOPE

        override fun evaluate(inputs: EvaluationInputs): RuleResult? =
            if (inputs.values["internalTaxCode"] == "SR-STD") RuleResult("IN_SCOPE", terminal = true) else null
    }

    private val inputs = EvaluationInputs.of(mapOf("internalTaxCode" to "SR-STD", "entityRef" to "E-001"))
    private val evaluator = Evaluator(listOf(ScopeRule))

    private fun bindingAt(registry: com.dynokore.platform.rulepackkernel.PackRegistry) =
        PackResolver(registry).resolve(listOf(PackTypes.SCOPE), null, ResolutionDates(TransactionDate(LocalDate.of(2026, 3, 20))))
            .shouldBeInstanceOf<PackResolution.Resolved>().binding

    @Test
    fun `INV-AC-1728 repeated evaluation with the same inputs and pack version yields the same verdict and trace`() {
        val binding = bindingAt(registryOf(version(PackTypes.SCOPE, "2026.3", LocalDate.of(2026, 1, 1))))
        val first = evaluator.evaluate(binding, inputs)
        // a second run, as if on another day: the evaluator takes no clock, no store and no other service
        val second = evaluator.evaluate(binding, EvaluationInputs.of(mapOf("entityRef" to "E-001", "internalTaxCode" to "SR-STD")))
        second shouldBe first
        first.shouldBeInstanceOf<EvaluationOutcome.Decided>().evaluation.verdict shouldBe "IN_SCOPE"
    }

    @Test
    fun `INV-AC-1730 the trace names the rule, the pack version, the inputs and the verdict in business terms`() {
        val binding = bindingAt(registryOf(version(PackTypes.SCOPE, "2026.3", LocalDate.of(2026, 1, 1))))
        val trace = evaluator.evaluate(binding, inputs).shouldBeInstanceOf<EvaluationOutcome.Decided>().evaluation.trace
        val step = trace.steps.single()
        step.ruleId shouldBe "R-114"
        step.packVersion.value shouldBe "2026.3"
        step.inputs["internalTaxCode"] shouldBe "SR-STD"
        step.verdict shouldBe "IN_SCOPE"
        val catalogue =
            BusinessTermCatalogue(
                rules = mapOf("R-114" to BusinessRule("Scope", "Tax code {internalTaxCode} is checked.", mapOf("IN_SCOPE" to "in scope"))),
                inputLabels = mapOf("internalTaxCode" to "Internal tax code"),
            )
        val line = BusinessTermRenderer(catalogue).render(trace).lines.single()
        line shouldContain "Scope"
        line shouldContain "SR-STD"
        line shouldContain "in scope"
        line shouldContain "2026.3"
    }

    @Test
    fun `INV-AC-2151 an in-flight evaluation completes under the version it started with`() {
        val registry = registryOf(version(PackTypes.SCOPE, "2026.3", LocalDate.of(2026, 1, 1)))
        val inFlight = InFlightEvaluation(bindingAt(registry), inputs)
        // a newer pack is armed meanwhile
        registry.register(version(PackTypes.SCOPE, "2026.4", LocalDate.of(2026, 3, 1)))
        val done = inFlight.complete(evaluator).shouldBeInstanceOf<EvaluationOutcome.Decided>().evaluation
        done.trace.steps.single().packVersion.value shouldBe "2026.3"
        done.packVersionsApplied.single().version.value shouldBe "2026.3"
        // only a new evaluation, from a fresh resolution, sees the newer pack
        bindingAt(registry).applied.single().version.value shouldBe "2026.4"
    }

    @Test
    fun `no rule firing is no verdict and no trace`() {
        val binding = bindingAt(registryOf(version(PackTypes.SCOPE, "2026.3", LocalDate.of(2026, 1, 1))))
        evaluator.evaluate(binding, EvaluationInputs.of(mapOf("internalTaxCode" to "ZR"))) shouldBe EvaluationOutcome.NoRuleFired
    }
}
