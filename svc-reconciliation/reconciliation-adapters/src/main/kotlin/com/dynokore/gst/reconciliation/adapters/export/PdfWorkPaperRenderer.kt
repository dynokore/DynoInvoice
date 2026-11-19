package com.dynokore.gst.reconciliation.adapters.export

import com.dynokore.gst.reconciliation.application.workpaper.export.ExportContentTypes
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportFormat
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportRows
import com.dynokore.gst.reconciliation.application.workpaper.export.RenderedExport
import com.dynokore.gst.reconciliation.application.workpaper.export.WorkPaperExport
import com.dynokore.gst.reconciliation.application.workpaper.export.WorkPaperRenderer
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream

/**
 * The export text holds a character the standard font cannot draw, or a control character. The message names the
 * field and the row only (for example `Items row 3 evidence`); it never holds a character or value of the export
 * (OWASP ASVS V7, NIST SP 800-53 SI-10).
 */
class UnsupportedExportTextException(
    message: String,
) : RuntimeException(message)

/**
 * PDF writer of the work-paper export (INV-REQ-982). A4 landscape, standard font Helvetica; the items are read only
 * through [ExportRows.of]. Every text is checked against the font before any page is drawn (fail closed: nothing is
 * replaced, no font is embedded). The document uses a temporary-file-backed stream cache (bounded memory) and is
 * closed in a `finally`. No clock is read and no date is stored, so the content depends on the export only.
 */
@Suppress("MagicNumber") // the column widths of the four tables, in points on an A4 landscape page
class PdfWorkPaperRenderer : WorkPaperRenderer {
    override fun render(
        export: WorkPaperExport,
        format: ExportFormat,
    ): RenderedExport {
        require(format == ExportFormat.PDF) { "PdfWorkPaperRenderer renders only PDF, not $format" }
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val layout = layout(export)
        check(layout, font)
        val document = PDDocument(IOUtils.createTempFileOnlyStreamCache())
        try {
            PageWriter(document, font).use { writer -> layout.forEach { writer.draw(it) } }
            val out = ByteArrayOutputStream()
            document.save(out)
            return RenderedExport(ExportContentTypes.PDF, out.toByteArray())
        } finally {
            document.close()
        }
    }

    /** A cell is a list of lines; a row is a list of cells (one per column). */
    private class Table(
        val name: String,
        val columns: List<Column>,
        val rows: List<List<List<String>>>,
    )

    private class Column(
        val name: String,
        val width: Float,
    )

    private sealed interface Block {
        class Summary(
            val lines: List<Pair<String, String>>,
        ) : Block

        class Grid(
            val table: Table,
        ) : Block
    }

    private fun layout(export: WorkPaperExport): List<Block> =
        listOf(
            Block.Summary(summary(export)),
            Block.Grid(boxes(export)),
            Block.Grid(items(export)),
            Block.Grid(relaxed(export)),
            Block.Summary(attestation(export)),
            Block.Grid(unmapped(export)),
        )

    private fun summary(export: WorkPaperExport) =
        listOf(
            "run" to export.runId,
            "entity" to export.entityRef,
            "period" to export.periodRef,
            "prepared at" to export.preparedAt,
            "return version" to export.returnVersion?.toString().orEmpty(),
            "return form" to export.returnForm.orEmpty(),
            "unexplained residual" to export.unexplainedResidual,
        )

    private fun attestation(export: WorkPaperExport): List<Pair<String, String>> {
        val attestation = export.attestation ?: return listOf("attestation" to "not attested")
        return listOf("attested by" to attestation.attestedBy, "attested at" to attestation.attestedAt)
    }

    private fun boxes(export: WorkPaperExport) =
        Table(
            "Boxes",
            listOf(
                Column("box", 40f),
                Column("submitted", 80f),
                Column("filed", 80f),
                Column("variance", 80f),
                Column("variance class", 150f),
                Column("supporting documents", 340f),
            ),
            export.boxes.map { b ->
                listOf(
                    listOf(b.box),
                    listOf(b.submittedAmount),
                    listOf(b.filedAmount),
                    listOf(b.variance),
                    b.varianceClasses.map { "${it.varianceClass} ${it.amount}" },
                    b.supportingDocuments,
                )
            },
        )

    private fun items(export: WorkPaperExport) =
        Table(
            "Items",
            listOf(
                Column("box", 40f),
                Column("document", 170f),
                Column("version", 40f),
                Column("line", 40f),
                Column("amount", 70f),
                Column("evidence", 410f),
            ),
            ExportRows.of(export).map { r ->
                listOf(
                    listOf(r.box),
                    listOf(r.docUuid),
                    listOf(r.version.toString()),
                    listOf(r.lineNumber.toString()),
                    listOf(r.amount),
                    r.evidenceRefs,
                )
            },
        )

    private fun relaxed(export: WorkPaperExport) =
        Table(
            "Relaxed controls",
            listOf(Column("code", 100f), Column("description", 670f)),
            export.relaxedControls.map { listOf(listOf(it.code), listOf(it.description)) },
        )

    private fun unmapped(export: WorkPaperExport) =
        Table(
            "Unmapped lines",
            listOf(Column("document", 250f), Column("version", 80f), Column("line", 80f), Column("tax code", 100f)),
            export.unmappedLines.map {
                listOf(
                    listOf(it.docUuid),
                    listOf(it.version.toString()),
                    listOf(it.lineNumber.toString()),
                    listOf(it.taxCode),
                )
            },
        )

    /** Encodes every text with the font before any page is drawn; the first failure names the field and row only. */
    private fun check(
        layout: List<Block>,
        font: PDType1Font,
    ) {
        layout.forEach { block ->
            when (block) {
                is Block.Summary -> {
                    block.lines.forEach { (label, value) -> checkText(font, value, "Summary $label") }
                }

                is Block.Grid -> {
                    block.table.columns.forEach { checkText(font, it.name, "${block.table.name} header ${it.name}") }
                    block.table.rows.forEachIndexed { r, row ->
                        row.forEachIndexed { c, cell ->
                            cell.forEach {
                                checkText(
                                    font,
                                    it,
                                    "${block.table.name} row ${r + 1} ${block.table.columns[c].name}",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkText(
        font: PDType1Font,
        text: String,
        where: String,
    ) {
        val control = text.codePoints().anyMatch { Character.isISOControl(it) }
        val encodable =
            !control &&
                try {
                    font.encode(text)
                    true
                } catch (_: IllegalArgumentException) {
                    false
                }
        if (!encodable) throw UnsupportedExportTextException(where)
    }

    /** Draws the blocks onto A4 landscape pages; each page that holds table rows repeats the table header. */
    private class PageWriter(
        private val document: PDDocument,
        private val font: PDType1Font,
    ) : AutoCloseable {
        private var stream: PDPageContentStream? = null
        private var y = 0f

        fun draw(block: Block) {
            when (block) {
                is Block.Summary -> {
                    block.lines.forEach { (label, value) ->
                        ensure(LINE_TABLE)
                        text(MARGIN, "$label: $value", TABLE_SIZE)
                        y -= LINE_TABLE
                    }
                    y -= LINE_TABLE
                }

                is Block.Grid -> {
                    grid(block.table)
                }
            }
        }

        private fun grid(table: Table) {
            ensure(LINE_TITLE + LINE_TABLE * 2)
            text(MARGIN, table.name, TITLE_SIZE)
            y -= LINE_TITLE
            header(table)
            table.rows.forEach { row -> rowLines(table, row) }
            y -= LINE_TABLE
        }

        private fun header(table: Table) {
            var x = MARGIN
            table.columns.forEach {
                text(x, it.name, TABLE_SIZE)
                x += it.width
            }
            y -= LINE_TABLE
        }

        private fun rowLines(
            table: Table,
            row: List<List<String>>,
        ) {
            val wrapped = row.mapIndexed { c, cell -> cell.flatMap { wrap(it, table.columns[c].width - GUTTER) } }
            val height = maxOf(1, wrapped.maxOf { it.size })
            if (height > linesLeft() && height <= PAGE_LINES) newPage(table)
            var from = 0
            while (from < height) {
                if (linesLeft() < 1) newPage(table)
                val take = minOf(height - from, linesLeft())
                var x = MARGIN
                wrapped.forEachIndexed { c, lines ->
                    lines.drop(from).take(take).forEachIndexed {
                        i,
                        line,
                        ->
                        text(x, line, TABLE_SIZE, y - i * LINE_TABLE)
                    }
                    x += table.columns[c].width
                }
                y -= take * LINE_TABLE
                from += take
            }
        }

        /** Breaks [text] into lines that fit [width]; a text with no break point is cut by character, never dropped. */
        private fun wrap(
            text: String,
            width: Float,
        ): List<String> {
            if (text.isEmpty()) return emptyList()
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            var used = 0f
            text.codePoints().forEach { cp ->
                val ch = String(Character.toChars(cp))
                val w = font.getStringWidth(ch) / THOUSAND * TABLE_SIZE
                if (used + w > width && line.isNotEmpty()) {
                    lines += line.toString()
                    line.setLength(0)
                    used = 0f
                }
                line.append(ch)
                used += w
            }
            lines += line.toString()
            return lines
        }

        private fun linesLeft(): Int = if (stream == null) 0 else ((y - BOTTOM) / LINE_TABLE).toInt()

        private fun ensure(needed: Float) {
            if (stream == null || y - needed < BOTTOM) newPage(null)
        }

        private fun newPage(table: Table?) {
            stream?.close()
            val page = PDPage(PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width))
            document.addPage(page)
            stream = PDPageContentStream(document, page)
            y = page.mediaBox.height - MARGIN
            if (table != null) header(table)
        }

        private fun text(
            x: Float,
            value: String,
            size: Float,
            at: Float = y,
        ) {
            val s = stream ?: error("no page")
            s.beginText()
            s.setFont(font, size)
            s.newLineAtOffset(x, at)
            s.showText(value)
            s.endText()
        }

        override fun close() {
            stream?.close()
            stream = null
        }

        private companion object {
            const val MARGIN = 36f
            const val BOTTOM = 36f
            const val TABLE_SIZE = 7f
            const val TITLE_SIZE = 9f
            const val LINE_TABLE = 9f
            const val LINE_TITLE = 14f
            const val GUTTER = 4f
            const val THOUSAND = 1000f
            const val PAGE_LINES = 55
        }
    }
}
