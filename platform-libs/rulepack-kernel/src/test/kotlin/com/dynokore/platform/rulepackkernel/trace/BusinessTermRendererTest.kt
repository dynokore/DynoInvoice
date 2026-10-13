package com.dynokore.platform.rulepackkernel.trace

import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** INV-REQ-1182, INV-REQ-777: a decision trace rendered in business terms. */
class BusinessTermRendererTest {
    private val catalogue =
        BusinessTermCatalogue(
            rules =
                mapOf(
                    "PINT-R-001" to
                        BusinessRule(
                            "Customer tax registration",
                            "The customer {customer} has registration {gstNo}.",
                            mapOf("BLOCK" to "Blocked: fix the customer's GST registration number"),
                        ),
                ),
            inputLabels = mapOf("customer" to "Customer"),
        )

    private fun step(
        rule: String,
        inputs: Map<String, String>,
        verdict: String = "BLOCK",
    ) = TraceStep(rule, PackId("pint-sg"), PackVersionLabel("2026.10.1"), inputs, verdict)

    private fun trace(vararg steps: TraceStep) = DecisionTrace(steps.toList())

    @Test
    fun `renders the rule, its inputs, the pack version and the verdict in business words`() {
        val rendered =
            BusinessTermRenderer(catalogue).render(
                trace(step("PINT-R-001", mapOf("customer" to "Acme Pte Ltd", "gstNo" to "M2-1234567-8"))),
            )

        assertEquals(
            listOf(
                "Customer tax registration: The customer Acme Pte Ltd has registration M2-1234567-8. " +
                    "Result: Blocked: fix the customer's GST registration number (pack pint-sg 2026.10.1)",
            ),
            rendered.lines,
        )
        assertEquals(emptyList<String>(), rendered.untranslatedRules)
    }

    @Test
    fun `an input the trace did not record is said so, not hidden`() {
        val partial = trace(step("PINT-R-001", mapOf("customer" to "Acme")))
        val line = BusinessTermRenderer(catalogue).render(partial).lines.single()
        assertEquals(true, line.contains("registration (not recorded)."))
    }

    @Test
    fun `a rule without business wording is shown by id and reported, rendering never fails`() {
        val rendered = BusinessTermRenderer(catalogue).render(trace(step("SCH-9", mapOf("customer" to "Acme"))))

        assertEquals(listOf("SCH-9"), rendered.untranslatedRules)
        val prefix = "Rule SCH-9 (no business description) on Customer = Acme gave BLOCK"
        assertEquals(true, rendered.lines.single().startsWith(prefix))
        assertFalse(rendered.lines.single().isBlank())
    }

    @Test
    fun `a trace needs at least one step and a reference is never blank`() {
        assertThrows(IllegalArgumentException::class.java) { DecisionTrace(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { TraceReference(" ") }
    }
}
