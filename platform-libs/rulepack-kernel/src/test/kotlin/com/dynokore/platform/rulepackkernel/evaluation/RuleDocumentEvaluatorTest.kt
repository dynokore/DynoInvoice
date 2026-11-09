package com.dynokore.platform.rulepackkernel.evaluation

import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.rulepackkernel.dryrun.DryRun
import com.dynokore.platform.rulepackkernel.dryrun.SampleDocument
import com.dynokore.platform.rulepackkernel.dryrun.Side
import com.dynokore.platform.rulepackkernel.dryrun.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class RuleDocumentEvaluatorTest {
    private val applied = listOf(AppliedPack(PackType("Scope"), PackId("scope-pack"), PackVersionLabel("2026.3")))

    private class CodeRule(
        override val ruleId: String,
        private val code: String,
        private val verdict: String,
    ) : Rule {
        override val packType: PackType = PackType("Scope")

        override fun evaluate(inputs: EvaluationInputs): RuleResult? =
            if (inputs.values["code"] == code) RuleResult(verdict) else null
    }

    private val rules = listOf(CodeRule("R-1", "SR", "IN_SCOPE"))
    private val plain = Evaluator(rules)
    private val evaluator = RuleDocumentEvaluator(plain, applied)

    private fun doc(
        id: String = "D-1",
        vararg extra: Pair<String, String>,
        base: Map<String, String> = mapOf("code" to "SR", "taxableValue" to "2.50", "gstValue" to "-12.5"),
    ) = SampleDocument(id, "2026-03", base + extra)

    private fun withGst(text: String) = doc(base = mapOf("code" to "SR", "taxableValue" to "1", "gstValue" to text))

    @Test
    fun `INV-AC-19139 a firing document gets the rule verdict as code, the stated amounts and the trace`() {
        val document = doc()
        val verdict = evaluator.evaluate(document)
        val expected =
            assertInstanceOf(
                EvaluationOutcome.Decided::class.java,
                plain.evaluate(applied, EvaluationInputs.of(document.inputs)),
            ).evaluation
        assertEquals("IN_SCOPE", verdict.code)
        assertEquals(BigDecimal("2.50"), verdict.taxableValue)
        assertEquals(BigDecimal("-12.5"), verdict.gstValue)
        assertEquals(expected.trace, verdict.trace)
    }

    @Test
    fun `INV-AC-19139 a document no rule fires on throws NoRuleFiredException and DryRun lists a failure`() {
        val document = doc(base = mapOf("code" to "ZR", "taxableValue" to "1", "gstValue" to "1"))
        assertThrows(NoRuleFiredException::class.java) { evaluator.evaluate(document) }
        val onlyCandidate = DryRun.run(listOf(document), DocumentEvaluatorOk, evaluator)
        assertEquals(1, onlyCandidate.failures.size)
        assertEquals(Side.CANDIDATE, onlyCandidate.failures.single().side)
        assertEquals("D-1", onlyCandidate.failures.single().documentId)
        assertEquals(0, onlyCandidate.unchanged)
        assertTrue(onlyCandidate.changes.isEmpty())
    }

    private object DocumentEvaluatorOk : com.dynokore.platform.rulepackkernel.dryrun.DocumentEvaluator {
        override fun evaluate(document: SampleDocument): Verdict = Verdict("X", BigDecimal.ONE, BigDecimal.ONE, trace())

        private fun trace() =
            com.dynokore.platform.rulepackkernel.trace.DecisionTrace(
                listOf(
                    com.dynokore.platform.rulepackkernel.trace.TraceStep(
                        "R",
                        PackId("p"),
                        PackVersionLabel("1"),
                        emptyMap(),
                        "X",
                    ),
                ),
            )
    }

    @Test
    fun `INV-AC-19139 a missing, look-alike or malformed amount fails the document, no default zero`() {
        val good = mapOf("code" to "SR", "taxableValue" to "1", "gstValue" to "1")
        val bad =
            listOf(
                good - "taxableValue",
                good - "gstValue",
                good - "taxableValue" + ("TaxableValue" to "1"),
                good - "taxableValue" + ("taxable_value" to "1"),
                good - "taxableValue" + ("taxableValue " to "1"),
            ) +
                listOf("", "abc", "1,5", "1.234", "+5", ".5", "1".repeat(18), "5\n", " 5", "5 ", "٥", "-")
                    .map { good + ("gstValue" to it) }
        bad.forEach { inputs ->
            assertThrows(IllegalArgumentException::class.java, {
                evaluator.evaluate(SampleDocument("D-1", "p", inputs))
            }, inputs.toString())
        }
        listOf(
            "0",
            "100.00",
            "-12.5",
        ).forEach { assertEquals(BigDecimal(it), withGst(it).let(evaluator::evaluate).gstValue) }
    }

    @Test
    fun `INV-AC-19139 the same document gives an equal Verdict twice and in a fresh instance`() {
        val first = evaluator.evaluate(doc())
        assertEquals(first, evaluator.evaluate(doc()))
        assertEquals(
            first,
            RuleDocumentEvaluator(Evaluator(listOf(CodeRule("R-1", "SR", "IN_SCOPE"))), applied).evaluate(doc()),
        )
    }

    @Test
    fun `INV-AC-19139 two rules firing with different verdicts throw RuleConflictException, DryRun lists a failure`() {
        fun conflicting(second: String) =
            RuleDocumentEvaluator(
                Evaluator(listOf(CodeRule("R-1", "SR", "IN_SCOPE"), CodeRule("R-2", "SR", second))),
                applied,
            )
        val document = doc()
        val ex = assertThrows(RuleConflictException::class.java) { conflicting("OUT_OF_SCOPE").evaluate(document) }
        assertFalse(NoRuleFiredException::class.java.isInstance(ex))
        assertTrue(ex.message!!.contains("D-1") && ex.message!!.contains("R-1,R-2"))
        assertThrows(RuleConflictException::class.java) { conflicting("in_scope").evaluate(document) }
        val delta = DryRun.run(listOf(document), DocumentEvaluatorOk, conflicting("OUT_OF_SCOPE"))
        assertEquals(1, delta.failures.size)
        assertEquals(Side.CANDIDATE, delta.failures.single().side)
        assertEquals("D-1", delta.failures.single().documentId)
        assertEquals("RuleConflictException", delta.failures.single().message)
        assertEquals(0, delta.unchanged)
        assertTrue(delta.changes.isEmpty())
        assertEquals("IN_SCOPE", conflicting("IN_SCOPE").evaluate(document).code)
    }
}
