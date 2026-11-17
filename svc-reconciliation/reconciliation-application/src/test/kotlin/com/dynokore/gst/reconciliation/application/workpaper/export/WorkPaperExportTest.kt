package com.dynokore.gst.reconciliation.application.workpaper.export

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WorkPaperExportTest {
    private fun item(
        doc: String = "D1",
        version: Int = 1,
        line: Int = 1,
        amount: String = "100.00",
        evidence: List<String> = listOf("EV-1"),
    ) = ExportReconcilingItem(doc, version, line, amount, evidence)

    private fun box(
        name: String = "1",
        docs: List<String> = listOf("D1", "D2"),
        items: List<ExportReconcilingItem> = listOf(item()),
    ) = ExportBox(name, "100.00", "100.00", "0.00", null, docs, items)

    private fun export(
        entity: String = "E-1",
        residual: String = "0.00",
        boxes: List<ExportBox> =
            listOf(
                box(
                    items =
                        listOf(
                            item("D2", 1, 2, "-5.50", listOf("EV-2", "EV-3")),
                            item("D1", 1, 1, "100.00", listOf("EV-1")),
                        ),
                ),
                box("6", listOf("D1"), listOf(item("D1", 1, 3, "7.00", listOf("EV-4")))),
            ),
    ) = WorkPaperExport(
        "R1",
        entity,
        "2026-Q1",
        "2026-04-01T00:00:00Z",
        null,
        null,
        boxes,
        residual,
        emptyList(),
        null,
        emptyList(),
    )

    @Test
    fun `INV-AC-24293 rows are in box then document version line order`() {
        ExportRows.of(export()) shouldBe
            listOf(
                ExportRow("1", "D1", 1, 1, "100.00", listOf("EV-1")),
                ExportRow("1", "D2", 1, 2, "-5.50", listOf("EV-2", "EV-3")),
                ExportRow("6", "D1", 1, 3, "7.00", listOf("EV-4")),
            )
    }

    @Test
    fun `INV-AC-24293 many items are sorted by document version and line`() {
        val docs = (1..30).map { "D$it" }
        val items = docs.flatMap { d -> listOf(3, 1, 2).map { v -> item(d, v, 10 - v) } }.reversed()
        val rows = ExportRows.of(export(boxes = listOf(box("1", docs, items))))
        rows.map { Triple(it.docUuid, it.version, it.lineNumber) } shouldBe
            items
                .map { Triple(it.docUuid, it.version, it.lineNumber) }
                .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
    }

    @Test
    fun `INV-AC-24293 second read equals the first`() {
        val e = export()
        ExportRows.of(e) shouldBe ExportRows.of(e)
    }

    @Test
    fun `INV-AC-24293 box order is the listed order not a sorted one`() {
        val e = export(boxes = listOf(box("6", listOf("D1")), box("1", listOf("D1"))))
        ExportRows.of(e).map { it.box } shouldBe listOf("6", "1")
    }

    @Test
    fun `INV-AC-24293 empty evidence list is refused`() {
        val ex = assertThrows<IllegalArgumentException> { item(evidence = emptyList()) }
        ex.message!!.contains("evidenceRefs") shouldBe true
    }

    @Test
    fun `INV-AC-24293 blank evidence reference is refused`() {
        assertThrows<IllegalArgumentException> { item(evidence = listOf("EV-1", " ")) }
    }

    @Test
    fun `INV-AC-24293 item document missing from the box list is refused`() {
        val ex = assertThrows<IllegalArgumentException> { box(items = listOf(item(doc = "D3"))) }
        ex.message!!.contains("D3") shouldBe true
    }

    @Test
    fun `INV-AC-24293 duplicate box is refused`() {
        assertThrows<IllegalArgumentException> { export(boxes = listOf(box("1"), box("1"))) }
    }

    @Test
    fun `INV-AC-24293 amount 1_234 is refused`() {
        assertThrows<IllegalArgumentException> { item(amount = "1.234") }
        assertThrows<IllegalArgumentException> { export(residual = "1.234") }
    }

    @Test
    fun `INV-AC-24293 amount with 18 integer digits is refused and 17 accepted`() {
        assertThrows<IllegalArgumentException> { item(amount = "1".repeat(18)) }
        item(amount = "1".repeat(17)).amount.length shouldBe 17
    }

    @Test
    fun `INV-AC-24293 blank entity is refused`() {
        val ex = assertThrows<IllegalArgumentException> { export(entity = " ") }
        ex.message!!.contains("entityRef") shouldBe true
    }

    @Test
    fun `INV-AC-24293 version 0 and line 0 are refused`() {
        assertThrows<IllegalArgumentException> { item(version = 0) }
        assertThrows<IllegalArgumentException> { item(line = 0) }
    }

    @Test
    fun `INV-AC-24293 amount with trailing newline is refused`() {
        assertThrows<IllegalArgumentException> { item(amount = "1.00\n") }
    }

    @Test
    fun `INV-AC-24293 unmapped line with blank document, version 0, line 0 or blank tax code is refused`() {
        ExportUnmappedLine("D1", 1, 1, "S").docUuid shouldBe "D1"
        assertThrows<IllegalArgumentException> { ExportUnmappedLine(" ", 1, 1, "S") }
        assertThrows<IllegalArgumentException> { ExportUnmappedLine("D1", 0, 1, "S") }
        assertThrows<IllegalArgumentException> { ExportUnmappedLine("D1", 1, 0, "S") }
        assertThrows<IllegalArgumentException> { ExportUnmappedLine("D1", 1, 1, "") }
    }
}
