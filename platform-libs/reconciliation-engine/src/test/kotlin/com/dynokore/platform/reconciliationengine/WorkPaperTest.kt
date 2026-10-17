package com.dynokore.platform.reconciliationengine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class WorkPaperTest {
    private val spec = WorkPaperSpec(setOf("entity", "period"))
    private val header = mapOf("entity" to "E1", "period" to "2026Q1", "preparer" to "p")
    private val engine = ReconciliationEngine(setOf(EXCLUDED), listOf(rule(EXCLUDED, "BOX_3", "k")))
    private val result =
        engine.reconcile(
            mapOf("BOX_3" to d("500.00"), "BOX_1" to d("9")),
            emptyMap(),
            mapOf("BOX_3" to listOf(Rec("EV-77", d("500.00"), "k"))),
        )
    private val att = listOf(AttestationEntry("PREPARER", "u1", Instant.parse("2026-10-17T00:00:00Z")))

    private fun build(
        h: Map<String, String> = header,
        a: List<AttestationEntry> = att,
        rt: String = "v3",
    ) = WorkPaper.build(spec, h, result, a, rt)

    @Test
    fun `refusals`() {
        assertThrows(IllegalArgumentException::class.java) { build(h = header - "period") }
        assertThrows(IllegalArgumentException::class.java) { build(rt = " ") }
        listOf(AttestationEntry(" ", "u", Instant.EPOCH), AttestationEntry("R", "", Instant.EPOCH)).forEach {
            assertThrows(IllegalArgumentException::class.java) { build(a = listOf(it)) }
        }
    }

    @Test
    fun `a host header key equal to the reserved key is refused with a named error`() {
        val e = assertThrows(ReservedHeaderKeyException::class.java) { build(h = header + ("reconciledTo" to "x")) }
        assertEquals("reconciledTo", e.key)
        build(h = header + ("reconciledTo2" to "x") + ("ReconciledTo" to "x"))
    }

    @Test
    fun `sections are separate and an empty attestation is not attested`() {
        val w = build(a = emptyList())
        assertFalse(w.attested)
        assertTrue(build().attested)
        assertEquals(listOf("BOX_1"), w.unexplainedResidual.map { it.key })
        assertEquals(listOf("BOX_3"), w.classifiedVariances.map { it.key })
        assertEquals("v3", w.reconciledTo)
    }

    @Test
    fun `zero unexplained lines give an empty residual section`() {
        val r = engine.reconcile(mapOf("BOX_3" to d("500.00")), emptyMap(), ev("BOX_3", Rec("EV-77", d("500.00"), "k")))
        assertTrue(WorkPaper.build(spec, header, r, att, "v1").unexplainedResidual.isEmpty())
    }

    @Test
    fun `rows keep the fixed section order, carry linked references and are repeatable`() {
        val rows = build().toRows()
        assertEquals(
            listOf("header", "comparison", "classified variances", "unexplained residual", "attestation"),
            rows.map { it.section }.distinct(),
        )
        assertTrue(rows.any { it.section == "classified variances" && "EV-77" in it.cells.joinToString(";") })
        assertEquals(rows, build().toRows())
    }
}
