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
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
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
            s
                .map { it.fileName.toString() }
                .filter { it.startsWith("PDFBox") }
                .toList()
                .toSet()
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

    private val tamilWord = "\u0BA4\u0BAE\u0BBF\u0BB4\u0BCD"

    /** One variant per field: builds the export with the given field value, and the expected input text. */
    private fun variants(): List<Pair<(String) -> WorkPaperExport, String>> =
        listOf(
            { v: String -> fixture(entity = v) } to "E1\u00E9\u00F1",
            { v: String -> fixture(box = v) } to "B1\u00FC",
            { v: String -> fixture(doc = v) } to "D1-\u00DF\u00E7",
            { v: String -> fixture(evidence = v) } to "ev-$tamilWord",
            { v: String -> fixture(description = v) } to "late filing $tamilWord",
            { v: String -> fixture(attester = v) } to "U7",
            { v: String -> fixture(description = v) } to "mix $tamilWord \u00E9 7",
        )

    /** The font name of every extracted character, by character. */
    private fun fontsPerChar(bytes: ByteArray): List<Pair<String, String>> {
        val seen = mutableListOf<Pair<String, String>>()
        Loader.loadPDF(bytes).use { doc ->
            object : PDFTextStripper() {
                override fun writeString(
                    text: String,
                    textPositions: MutableList<TextPosition>,
                ) {
                    // a Tamil run carries its input as ActualText: PDFBox reports the whole text on its first
                    // glyph
                    // and an empty string on the glyphs that follow, so those have no character of their own
                    textPositions.filter { it.unicode.isNotEmpty() }.forEach { seen += it.unicode to it.font.name }
                }
            }.getText(doc)
        }
        return seen
    }

    @Test
    fun `INV-AC-24552 Latin and Tamil texts extract back exactly in embedded subset fonts`() {
        variants().forEach { (build, text) ->
            val r = render(build(text))
            assertEquals(ExportContentTypes.PDF, r.contentType)
            assertEquals("%PDF-", String(r.bytes, 0, 5, Charsets.US_ASCII))
            assertTrue(r.bytes.size < 1_048_576)
            // string equality of the whole extracted text: the same export with a unique placeholder in the field,
            // the placeholder replaced by the input text without whitespace, must equal the variant's text
            val placeholder = text.take(2) + "QQ9" // same sort position as the text (items sort by document)
            val expected = pages(render(build(placeholder)).bytes).joinToString("")
            assertEquals(
                expected.replace(placeholder, text.filterNot { it.isWhitespace() }),
                pages(r.bytes).joinToString(""),
            )
            Loader.loadPDF(r.bytes).use { doc ->
                val names = doc.pages.flatMap { p -> p.resources.fontNames.map { p.resources.getFont(it) } }
                assertTrue(names.isNotEmpty())
                names.forEach {
                    assertTrue(it.isEmbedded)
                    assertTrue(Regex("[A-Z]{6}\\+(NotoSans-Regular|NotoSansTamil-Regular)").matches(it.name), it.name)
                }
            }
        }
    }

    @Test
    fun `INV-AC-24552 each character is drawn in the font of its script`() {
        val chars = fontsPerChar(render(fixture(evidence = "ev-${tamilWord}x1", entity = "E1\u00E9")).bytes)
        assertTrue(chars.isNotEmpty())
        chars.forEach { (c, font) ->
            val cp = c.codePointAt(0)
            if (cp in 0x0B80..0x0BFF) {
                assertTrue(font.endsWith("+NotoSansTamil-Regular"), "$cp $font")
            } else if (!Character.isWhitespace(cp)) {
                assertTrue(font.endsWith("+NotoSans-Regular"), "$cp $font")
            }
        }
        assertTrue(chars.any { it.second.endsWith("+NotoSansTamil-Regular") })
    }

    @Test
    fun `INV-AC-24552 Han, U+0378 and U+0007 are refused naming the field and row only`() {
        val e = assertThrows<UnsupportedExportTextException> { render(fixture(evidence = "ev-$HAN")) }
        assertEquals("Items row 2 evidence", e.message)
        val mixed = assertThrows<UnsupportedExportTextException> { render(fixture(description = "$HAN$tamilWord")) }
        assertEquals("Relaxed controls row 1 description", mixed.message)
        val unassigned = assertThrows<UnsupportedExportTextException> { render(fixture(evidence = "ev-\u0378")) }
        assertEquals("Items row 2 evidence", unassigned.message)
        refused(fixture(evidence = "ev\u0378"), "Items row 2 evidence", "\u0378")
        refused(fixture(description = "zq\u0007"), "Relaxed controls row 1 description", "zq")
    }

    @Test
    fun `INV-AC-24552 repeated renders and the 400 item variant with Tamil hold`() {
        val export = fixture(evidence = "ev-$tamilWord")
        assertEquals(pages(render(export).bytes), pages(render(export).bytes))
        val before = tmpListing()
        val big =
            big().let { b ->
                b.copy(
                    boxes =
                        b.boxes.map {
                            it.copy(
                                items =
                                    it.items.mapIndexed { i, x ->
                                        if (i ==
                                            0
                                        ) {
                                            x.copy(evidenceRefs = listOf(tamilWord))
                                        } else {
                                            x
                                        }
                                    },
                            )
                        },
                )
            }
        val pages = pages(render(big).bytes)
        assertTrue(pages.size > 1)
        pages.filter { it.contains("EV-") }.forEach { assertTrue(it.contains("boxdocumentversionlineamountevidence")) }
        assertEquals(before, tmpListing())
    }

    // ---- INV-AC-24750: Tamil runs drawn as HarfBuzz-shaped glyph ids ----

    /** Tamil fixtures: the text and the glyph ids that `hb-shape` 6.0.0 prints for Noto Sans Tamil 2.004. */
    private val shapedFixtures =
        listOf(
            "\u0B95\u0BC6" to listOf(46, 18),
            "\u0B95\u0BC7" to listOf(47, 18),
            "\u0B95\u0BC8" to listOf(48, 18),
            "\u0B95\u0BCA" to listOf(46, 18, 41),
            "\u0B95\u0BCB" to listOf(47, 18, 41),
            "\u0B95\u0BCC" to listOf(46, 18, 54),
            "\u0B95\u0BCD\u0BB7" to listOf(76),
        )

    /** The 16-bit codes shown with the Tamil font in the content streams, in order (before subsetting maps them). */
    private fun tamilCodes(bytes: ByteArray): List<Int> =
        Loader.loadPDF(bytes).use { doc ->
            val codes = mutableListOf<Int>()
            doc.pages.forEach { page ->
                var isTamil = false
                val tokens = PDFStreamParser(page).parse()
                tokens.forEachIndexed { i, token ->
                    if (token is Operator && token.name == "Tf") {
                        isTamil =
                            page.resources
                                .getFont(tokens[i - 2] as COSName)
                                .name
                                .endsWith("+NotoSansTamil-Regular")
                    } else if (token is COSString && isTamil) {
                        token.bytes.toList().chunked(2).forEach {
                            codes +=
                                (it[0].toInt() and 0xFF) * 256 + (it[1].toInt() and 0xFF)
                        }
                    }
                }
            }
            codes
        }

    private fun containsRun(
        codes: List<Int>,
        run: List<Int>,
    ) = (0..codes.size - run.size).any { codes.subList(it, it + run.size) == run }

    @Test
    fun `INV-AC-24750 the PDF made from each Tamil fixture has the shaped sequence in its content stream`() {
        shapedFixtures.forEach { (text, ids) ->
            val codes = tamilCodes(render(fixture(description = text)).bytes)
            assertTrue(containsRun(codes, ids), "$ids in $codes")
        }
    }

    @Test
    fun `INV-AC-24750 the text that PDFBox extracts from each Tamil fixture equals the input without whitespace`() {
        shapedFixtures.forEach { (text, _) ->
            val placeholder = "zqQQ9"
            val expected = pages(render(fixture(description = placeholder)).bytes).joinToString("")
            assertEquals(
                expected.replace(placeholder, text),
                pages(render(fixture(description = text)).bytes).joinToString(""),
                text,
            )
        }
    }

    @Test
    fun `INV-AC-24750 every Tamil font is embedded as a subset that holds the shaped glyphs`() {
        val bytes = render(fixture(description = shapedFixtures[3].first)).bytes
        Loader.loadPDF(bytes).use { doc ->
            val fonts = doc.pages.flatMap { p -> p.resources.fontNames.map { p.resources.getFont(it) } }
            val tamil = fonts.filter { it.name.endsWith("+NotoSansTamil-Regular") }
            assertTrue(tamil.isNotEmpty())
            tamil.forEach { assertTrue(it.isEmbedded) }
        }
    }

    @Test
    fun `INV-AC-24750 Han is still refused and an uncovered code point throws before any page is drawn`() {
        val han = assertThrows<UnsupportedExportTextException> { render(fixture(evidence = "ev-$HAN")) }
        assertEquals("Items row 2 evidence", han.message)
        val none = assertThrows<UnsupportedExportTextException> { render(fixture(description = "\u0B95\u0BCA\u0378")) }
        assertEquals("Relaxed controls row 1 description", none.message)
    }

    @Test
    fun `INV-AC-24750 the Latin fixtures give the same text and page count as before`() {
        val first = render(fixture(entity = "E1\u00E9\u00F1"))
        assertEquals(pages(first.bytes), pages(render(fixture(entity = "E1\u00E9\u00F1")).bytes))
        assertTrue(pages(first.bytes).joinToString("").contains("E1\u00E9\u00F1"))
        assertEquals(1, pages(first.bytes).size)
        assertTrue(pages(render(big()).bytes).size > 1)
    }
}
