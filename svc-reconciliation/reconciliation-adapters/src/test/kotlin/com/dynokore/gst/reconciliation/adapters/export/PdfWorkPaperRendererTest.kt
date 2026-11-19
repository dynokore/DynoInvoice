package com.dynokore.gst.reconciliation.adapters.export

import com.dynokore.gst.reconciliation.application.workpaper.WorkPaperVarianceShare
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportAttestation
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportBox
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportContentTypes
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportFormat
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportReconcilingItem
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportRelaxedControl
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportRows
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportUnmappedLine
import com.dynokore.gst.reconciliation.application.workpaper.export.WorkPaperExport
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path

/** The CJK character the standard font cannot encode, written without a literal (English-only sources). */
@Suppress("MagicNumber")
private val HAN = Character.toString(0x4E2D)

class PdfWorkPaperRendererTest {
    private val renderer = PdfWorkPaperRenderer()
    private val long = "E" + "x".repeat(118) + "Z"

    @Suppress("LongParameterList")
    private fun fixture(
        attestation: ExportAttestation? = ExportAttestation("U7", "2026-11-02T00:00:00Z"),
        entity: String = "E1",
        box: String = "B1",
        doc: String = "D1",
        evidence: String = "EV-2",
        description: String = "Relaxed one",
        attester: String? = null,
    ) = WorkPaperExport(
        runId = "R1",
        entityRef = entity,
        periodRef = "P1",
        preparedAt = "2026-11-01T00:00:00Z",
        returnVersion = 2,
        returnForm = "F5",
        boxes =
            listOf(
                ExportBox(
                    box,
                    "100.00",
                    "90.00",
                    "10.00",
                    listOf(WorkPaperVarianceShare("MATERIAL", "10.00")),
                    listOf(doc, "D2"),
                    listOf(
                        ExportReconcilingItem("D2", 1, 1, "3.00", listOf("EV-3")),
                        ExportReconcilingItem(doc, 1, 2, "4.50", listOf(evidence, "EV-9")),
                        ExportReconcilingItem(doc, 1, 1, "5.50", listOf("EV-1", long)),
                    ),
                ),
                ExportBox("B2", "0.00", "0.00", "0.00", emptyList(), emptyList(), emptyList()),
            ),
        unexplainedResidual = "0.00",
        relaxedControls =
            listOf(ExportRelaxedControl("RC-1", description), ExportRelaxedControl("RC-2", "Relaxed two")),
        attestation = attester?.let { ExportAttestation(it, "2026-11-02T00:00:00Z") } ?: attestation,
        unmappedLines = listOf(ExportUnmappedLine("D9", 1, 4, "XX")),
    )

    private fun big() =
        fixture().copy(
            boxes =
                listOf(
                    ExportBox(
                        "B1",
                        "1.00",
                        "1.00",
                        "0.00",
                        emptyList(),
                        listOf("D1"),
                        (1..400).map { ExportReconcilingItem("D1", 1, it, "$it.00", listOf("EV-$it")) },
                    ),
                ),
        )

    private fun pages(bytes: ByteArray): List<String> =
        Loader.loadPDF(bytes).use { doc ->
            val stripper = PDFTextStripper()
            (1..doc.numberOfPages).map { p ->
                stripper.startPage = p
                stripper.endPage = p
                stripper.getText(doc).filterNot { it.isWhitespace() }
            }
        }

    private fun render(export: WorkPaperExport) = renderer.render(export, ExportFormat.PDF)

    private fun tmpListing(): Set<String> =
        Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { s ->
            s.map { it.fileName.toString() }.toList().toSet()
        }

    @Test
    fun `INV-AC-24540 content type is PDF and the bytes start with the PDF magic`() {
        val r = render(fixture())
        assertEquals(ExportContentTypes.PDF, r.contentType)
        assertEquals("%PDF-", String(r.bytes, 0, 5, Charsets.US_ASCII))
    }

    @Test
    fun `INV-AC-24540 extracted text holds every section in order`() {
        val export = fixture()
        val text = pages(render(export).bytes).joinToString("")
        val expected =
            listOf("R1", "E1", "P1", "2026-11-01T00:00:00Z", "2", "F5", "0.00") +
                listOf("B1", "100.00", "90.00", "10.00", "MATERIAL10.00", "D1D2") +
                listOf("B2", "0.00") +
                ExportRows.of(export).flatMap { r ->
                    listOf(r.box, r.docUuid, r.version.toString(), r.lineNumber.toString(), r.amount) + r.evidenceRefs
                } +
                listOf("RC-1", "Relaxedone", "RC-2", "Relaxedtwo", "U7", "2026-11-02T00:00:00Z", "D9", "XX")
        var at = 0
        expected.forEach { token ->
            val found = text.indexOf(token, at)
            assertTrue(found >= 0, "missing or out of order: $token")
            at = found + token.length
        }
    }

    @Test
    fun `INV-AC-24540 item rows equal ExportRows of the export element by element`() {
        val export = fixture()
        val rows = ExportRows.of(export)
        val text = pages(render(export).bytes).joinToString("")
        val items = text.substring(text.indexOf("evidence", text.indexOf("Items")) + "evidence".length)
        val joined =
            rows.joinToString("") { r ->
                r.box + r.docUuid + r.version + r.lineNumber + r.amount + r.evidenceRefs.joinToString("")
            }
        assertTrue(items.startsWith(joined), "items table differs from ExportRows.of")
    }

    @Test
    fun `INV-AC-24540 the 120 character reference is present in full`() {
        assertEquals(120, long.length)
        assertTrue(pages(render(fixture()).bytes).joinToString("").contains(long))
    }

    @Test
    fun `INV-AC-24540 a missing attestation is written as not attested`() {
        val text = pages(render(fixture(attestation = null)).bytes).joinToString("")
        assertTrue(text.contains("notattested"))
        assertFalse(pages(render(fixture()).bytes).joinToString("").contains("notattested"))
    }

    @Test
    fun `INV-AC-24540 the 400 item variant spans pages and every item page repeats the column names`() {
        val export = big()
        val pages = pages(render(export).bytes)
        assertTrue(pages.size > 1)
        val header = "boxdocumentversionlineamountevidence"
        val itemPages = pages.filter { it.contains("EV-") }
        assertTrue(itemPages.size > 1)
        itemPages.forEach { assertTrue(it.contains(header), "item column names missing on a page") }
        assertEquals(400, ExportRows.of(export).size)
        assertTrue(pages.joinToString("").contains("EV-400"))
    }

    @Test
    fun `INV-AC-24540 two renders give equal extracted text per page and equal page count`() {
        val export = fixture()
        assertEquals(pages(render(export).bytes), pages(render(export).bytes))
        assertEquals(pages(render(big()).bytes), pages(render(big()).bytes))
    }

    @Test
    fun `INV-AC-24540 the information dictionary holds no date`() {
        Loader.loadPDF(render(fixture()).bytes).use {
            assertEquals(null, it.documentInformation.creationDate)
            assertEquals(null, it.documentInformation.modificationDate)
        }
    }

    private fun refused(
        export: WorkPaperExport,
        field: String,
        value: String,
    ) {
        val before = tmpListing()
        val e = assertThrows<UnsupportedExportTextException> { render(export) }
        assertTrue(e.message!!.contains(field), "message names ${e.message}")
        assertFalse(e.message!!.contains(value))
        assertFalse(e.message!!.contains(HAN))
        assertEquals(before, tmpListing())
    }

    @Test
    fun `INV-AC-24540 U+4E2D in the entity, box, document, evidence, description and attester is refused`() {
        val c = HAN
        refused(fixture(entity = c), "Summary entity", c)
        refused(fixture(box = "B$c"), "Boxes row 1 box", c)
        refused(fixture(doc = "D$c"), "Boxes row 1 supporting documents", c)
        refused(fixture(evidence = "EV$c"), "Items row", c)
        refused(fixture(description = "R$c"), "Relaxed controls row 1 description", c)
        refused(fixture(attester = "U$c"), "Summary attested by", c)
    }

    @Test
    fun `INV-AC-24540 the evidence row number is named and the control character U+0007 is refused`() {
        val e = assertThrows<UnsupportedExportTextException> { render(fixture(evidence = "EV$HAN")) }
        assertEquals("Items row 2 evidence", e.message)
        refused(fixture(description = "bell\u0007"), "Relaxed controls row 1 description", "bell")
    }

    @Test
    fun `INV-AC-24540 the SPREADSHEET call throws IllegalArgumentException naming the format`() {
        val e = assertThrows<IllegalArgumentException> { renderer.render(fixture(), ExportFormat.SPREADSHEET) }
        assertTrue(e.message!!.contains("SPREADSHEET"))
    }

    @Test
    fun `INV-AC-24540 no temporary file remains after a render`() {
        val before = tmpListing()
        render(big())
        assertEquals(before, tmpListing())
    }
}
