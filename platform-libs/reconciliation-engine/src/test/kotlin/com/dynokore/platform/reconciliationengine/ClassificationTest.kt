package com.dynokore.platform.reconciliationengine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClassificationTest {
    private fun run(
        ret: String,
        rules: List<ClassificationRule>,
        key: String,
        records: List<Rec>,
        lineKey: String = key,
    ): ReconciliationResult =
        ReconciliationEngine(setOf(EXCLUDED, PRE, OUTSIDE), rules)
            .reconcile(mapOf(lineKey to d(ret)), emptyMap(), mapOf(key to records))

    @Test
    fun `empty engine classifies nothing and reports every non-zero variance as unexplained`() {
        val r = ReconciliationEngine().reconcile(mapOf("A" to d("3"), "B" to d("0")), emptyMap())
        assertTrue(r.hasUnexplained)
        assertEquals(listOf("A"), r.unexplained.map { it.key })
        assertTrue(r.classifiedVariances.isEmpty())
    }

    @Test
    fun `a rule with an unknown category code fails construction`() {
        assertThrows(IllegalArgumentException::class.java) { RuleSet(setOf(PRE), listOf(rule(EXCLUDED, "A", "k"))) }
        assertThrows(IllegalArgumentException::class.java) { Category(" ", "x") }
    }

    @Test
    fun `fully explained gives residual 0_00 and no unexplained`() {
        val r = run("100.00", listOf(rule(EXCLUDED, "A", "k")), "A", listOf(Rec("R1", d("100.00"), "k")))
        assertEquals(
            "0.00",
            r.lines
                .single()
                .unexplainedResidual
                .toString(),
        )
        assertTrue(r.lines.single().fullyExplained)
        assertFalse(r.hasUnexplained)
        assertEquals(listOf("R1"), r.classifiedVariances.single().linkedRecords)
    }

    @Test
    fun `partly explained leaves 40_00`() {
        val r =
            run(
                "100.00",
                listOf(rule(EXCLUDED, "A", "k")),
                "A",
                listOf(Rec("R1", d("60.00"), "k"), Rec("R2", d("5"), "x")),
            )
        assertEquals(d("40.00"), r.lines.single().unexplainedResidual)
        assertTrue(r.hasUnexplained)
        assertEquals(d("60.00"), r.classifiedVariances.single().amount)
    }

    @Test
    fun `no matching rule leaves the whole variance with nothing classified`() {
        val r = run("100.00", listOf(rule(EXCLUDED, "A", "k")), "A", listOf(Rec("R1", d("100"), "other")))
        assertEquals(d("100.00"), r.lines.single().unexplainedResidual)
        assertTrue(r.classifiedVariances.isEmpty())
    }

    @Test
    fun `look-alike keys are not explained`() {
        listOf("B30", "B", "b3", "B3 ").forEach { k ->
            val r = run("100", listOf(rule(EXCLUDED, "B3", "k")), "B3", listOf(Rec("R", d("100"), "k")), lineKey = k)
            assertTrue(r.hasUnexplained, k)
            assertTrue(r.classifiedVariances.isEmpty(), k)
        }
    }

    @Test
    fun `over-explained gives a negative residual reported as unexplained`() {
        val r =
            run(
                "100",
                listOf(rule(EXCLUDED, "A", "k"), rule(PRE, "A", "j")),
                "A",
                listOf(Rec("R1", d("80"), "k"), Rec("R2", d("80"), "j")),
            )
        assertEquals(d("-60"), r.lines.single().unexplainedResidual)
        assertEquals(listOf("A"), r.unexplained.map { it.key })
        assertTrue(r.hasUnexplained)
    }

    @Test
    fun `residual zero is decided by compareTo`() {
        val r = run("100.0", listOf(rule(EXCLUDED, "A", "k")), "A", listOf(Rec("R", d("100.000"), "k")))
        assertTrue(r.lines.single().fullyExplained)
        assertFalse(r.hasUnexplained)
    }

    @Test
    fun `evidence of another key is not used`() {
        val r =
            ReconciliationEngine(setOf(EXCLUDED), listOf(rule(EXCLUDED, "A", "k")))
                .reconcile(mapOf("A" to d("5")), emptyMap(), mapOf("Z" to listOf(Rec("R", d("5"), "k"))))
        assertTrue(r.hasUnexplained)
    }

    // The examples of the criteria (INV-AC-677, 678, 679); test data only.
    private val engine =
        ReconciliationEngine(
            setOf(EXCLUDED, PRE, OUTSIDE),
            listOf(rule(EXCLUDED, "BOX_3", "exempt"), rule(PRE, "BOX_3", "before"), rule(OUTSIDE, "BOX_3", "baddebt")),
        )

    @Test
    fun `exempt financial service not submitted is EXCLUDED_TXN with residual 0_00`() {
        val evidence = ev("BOX_3", Rec("EV-1", d("500.00"), "exempt"))
        val r = engine.reconcile(mapOf("BOX_3" to d("500.00")), mapOf("BOX_3" to d("0")), evidence)
        assertEquals(
            "EXCLUDED_TXN",
            r.classifiedVariances
                .single()
                .category.code,
        )
        assertEquals(
            "0.00",
            r.lines
                .single()
                .unexplainedResidual
                .toString(),
        )
    }

    @Test
    fun `record before the obligation start is PRE_OBLIGATION and bad debt relief is OUTSIDE_BY_RULE`() {
        val pre = engine.reconcile(mapOf("BOX_3" to d("80")), emptyMap(), ev("BOX_3", Rec("D1", d("80"), "before")))
        assertEquals(
            "PRE_OBLIGATION",
            pre.classifiedVariances
                .single()
                .category.code,
        )
        val bd = engine.reconcile(mapOf("BOX_3" to d("30")), emptyMap(), ev("BOX_3", Rec("BD", d("30"), "baddebt")))
        assertEquals(
            "OUTSIDE_BY_RULE",
            bd.classifiedVariances
                .single()
                .category.code,
        )
        assertFalse(bd.hasUnexplained)
    }
}
