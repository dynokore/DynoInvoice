package com.dynokore.platform.rulepackkernel.dryrun

import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.rulepackkernel.trace.DecisionTrace
import com.dynokore.platform.rulepackkernel.trace.TraceStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** INV-REQ-772, INV-REQ-686, INV-REQ-703, INV-REQ-1352: the dry-run delta over a document sample. */
class DryRunTest {
    private fun trace(version: String): DecisionTrace {
        val step = TraceStep("RATE", PackId("gst-rates"), PackVersionLabel(version), emptyMap(), "applied")
        return DecisionTrace(listOf(step))
    }

    /** Rate-in-percent evaluator over the document's `net` input; code SR. */
    private fun evaluator(
        version: String,
        rate: Int,
    ) = DocumentEvaluator { doc ->
        val net = BigDecimal(doc.inputs.getValue("net"))
        Verdict("SR", net, net.multiply(BigDecimal(rate)).movePointLeft(2), trace(version))
    }

    private fun doc(
        id: String,
        period: String,
        net: String,
        straddles: Boolean = false,
    ) = SampleDocument(id, period, mapOf("net" to net), straddles)

    @Test
    fun `reports each changed document by period with old and new values, and lists straddling credit notes apart`() {
        val sample =
            listOf(
                doc("d1", "2026-Q3", "100.00"),
                doc("d2", "2026-Q3", "200.00"),
                doc("d3", "2026-Q4", "50.00"),
                doc("cn1", "2026-Q4", "-30.00", straddles = true),
            )
        val delta = DryRun.run(sample, evaluator("1", 9), evaluator("2", 10))

        assertEquals(4, delta.sampled)
        assertEquals(0, delta.unchanged)
        assertEquals(mapOf("2026-Q3" to 2, "2026-Q4" to 1), delta.changedByPeriod)
        assertEquals(listOf("cn1"), delta.straddling.map { it.document.id })
        val d1 = delta.changes.first { it.document.id == "d1" }
        assertEquals(0, BigDecimal("9.00").compareTo(d1.before.gstValue))
        assertEquals(0, BigDecimal("10.00").compareTo(d1.after.gstValue))
        assertEquals(0, BigDecimal("3.00").compareTo(delta.valueDeltaByPeriod.getValue("2026-Q3").gst))
    }

    @Test
    fun `a document whose outcome does not move is unchanged, whatever the scale or trace`() {
        val rescaled = DocumentEvaluator { Verdict("SR", BigDecimal("100"), BigDecimal("9.0"), trace("2")) }
        val delta = DryRun.run(listOf(doc("d1", "2026-Q3", "100.00")), evaluator("1", 9), rescaled)
        assertEquals(1, delta.unchanged)
        assertEquals(emptyList<DocumentChange>(), delta.changes)
    }

    @Test
    fun `a document that cannot be evaluated is a failure, never unchanged`() {
        val failing = DocumentEvaluator { throw IllegalStateException("no rate") }
        val delta = DryRun.run(listOf(doc("d1", "2026-Q3", "1")), evaluator("1", 9), failing)

        assertEquals(0, delta.unchanged)
        assertEquals(listOf(EvaluationFailure("d1", Side.CANDIDATE, "IllegalStateException")), delta.failures)
    }

    @Test
    fun `an empty sample is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            DryRun.run(emptyList(), evaluator("1", 9), evaluator("2", 9))
        }
    }
}
