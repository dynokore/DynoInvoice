package com.dynokore.platform.reconciliationengine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration

/**
 * INV-REQ-1362 / INV-AC-2327: the engine share of a one-million-document reconciliation, with a fixed workload and a
 * hard budget in the normal build.
 */
class ReconciliationScaleTest {
    private companion object {
        /** INV-REQ-1362: "under 5 minutes for 1M documents". The owner's published figure, not a tunable. */
        val BUDGET: Duration = Duration.ofMinutes(5)

        const val KEY_COUNT = 1_000_000
    }

    private class EvidenceWorkload(
        val returnSide: Map<String, BigDecimal>,
        val submittedSide: Map<String, BigDecimal>,
        val evidence: Map<String, List<EvidenceRecord>>,
        val rules: List<ClassificationRule>,
    )

    /** Each key: [amount] returned, nothing submitted, [recordsPerKey] records of equal amount that sum to it. */
    private fun evidenceWorkload(
        keyCount: Int,
        recordsPerKey: Int,
        amount: Int,
        dropOneRecord: Boolean = false,
    ): EvidenceWorkload {
        val keys = (0 until keyCount).map { "B" + it.toString().padStart(3, '0') }
        val each = BigDecimal(amount / recordsPerKey)
        val evidence =
            keys.associateWith { key ->
                (0 until recordsPerKey).map { Rec("$key-$it", each, "x") }
            }
        val kept = if (dropOneRecord) evidence + (keys[0] to evidence.getValue(keys[0]).drop(1)) else evidence
        return EvidenceWorkload(
            returnSide = keys.associateWith { BigDecimal(amount) },
            submittedSide = keys.associateWith { BigDecimal.ZERO },
            evidence = kept,
            rules = keys.map { rule(EXCLUDED, it, "x") },
        )
    }

    private fun timed(block: () -> Unit) {
        val start = System.nanoTime()
        assertTimeoutPreemptively(BUDGET) { block() }
        println("INV-AC-2327 elapsed ms: ${(System.nanoTime() - start) / 1_000_000}")
    }

    @Test
    fun `INV-AC-2327 one million keys reconcile inside the budget with correct totals`() {
        val returnSide = HashMap<String, BigDecimal>(KEY_COUNT * 2)
        val submittedSide = HashMap<String, BigDecimal>(KEY_COUNT * 2)
        var expectedVariance = BigDecimal.ZERO
        for (i in 0 until KEY_COUNT) {
            val key = "K" + i.toString().padStart(7, '0')
            val amount = BigDecimal(i + 1)
            returnSide[key] = amount
            if (i % 10 == 0) expectedVariance = expectedVariance.add(amount) else submittedSide[key] = amount
        }
        timed {
            val result = ReconciliationEngine().reconcile(returnSide, submittedSide)
            assertEquals(KEY_COUNT, result.lines.size)
            val missing = result.lines.count { it.comparison.missingSide == MissingSide.SUBMITTED }
            assertEquals(100_000, missing)
            assertEquals(missing, result.unexplained.size)
            assertEquals(
                "K0000000",
                result.lines
                    .first()
                    .comparison.key,
            )
            assertEquals(
                "K0999999",
                result.lines
                    .last()
                    .comparison.key,
            )
            val total = result.lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.comparison.variance) }
            assertEquals(0, expectedVariance.compareTo(total))
        }
    }

    @Test
    fun `INV-AC-2327 one million evidence records classify inside the budget with correct totals`() {
        val w = evidenceWorkload(keyCount = 1_000, recordsPerKey = 1_000, amount = 1_000)
        timed {
            val engine = ReconciliationEngine(setOf(EXCLUDED), w.rules)
            val result = engine.reconcile(w.returnSide, w.submittedSide, w.evidence)
            assertEquals(1_000, result.lines.size)
            assertFalse(result.hasUnexplained)
            assertEquals(1_000_000, result.classifiedVariances.sumOf { it.linkedRecords.size })
            assertTrue(result.classifiedVariances.all { it.amount.compareTo(BigDecimal(1_000)) == 0 })
        }
    }

    @Test
    fun `INV-AC-2327 the evidence assertions can fail on a small workload`() {
        val engine = ReconciliationEngine(setOf(EXCLUDED), evidenceWorkload(3, 2, 2).rules)
        val full = evidenceWorkload(3, 2, 2)
        assertFalse(engine.reconcile(full.returnSide, full.submittedSide, full.evidence).hasUnexplained)
        val short = evidenceWorkload(3, 2, 2, dropOneRecord = true)
        val result = engine.reconcile(short.returnSide, short.submittedSide, short.evidence)
        assertTrue(result.hasUnexplained)
        assertEquals(1, result.unexplained.size)
        assertEquals(0, BigDecimal.ONE.compareTo(result.unexplained.single().residual))
    }
}
