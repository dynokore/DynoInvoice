package com.dynokore.gst.submission.adapters.export

import com.dynokore.gst.submission.domain.listing.ExportTable
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.OutputStream

/**
 * A text of the export that the standard font Helvetica (WinAnsi) cannot encode. It names the [column] and the
 * 1-based [row] number (0 for a header or column-name text) and holds no cell text (INV-AC-16347).
 */
class UnsupportedExportTextException(
    val column: String,
    val row: Int,
) : RuntimeException("export text in column '$column' at row $row cannot be drawn in the PDF font")

/**
 * Writes the format-neutral [ExportTable] as a paged PDF (INV-REQ-978, INV-AC-16347): A4 landscape, Helvetica 7 pt,
 * the header block on the first page, then the column names (repeated on every page) and the rows. A text wider than
 * its column wraps onto further lines inside its row, so no cell text is cut. Every text is checked against the font
 * encoding before the first byte is written; one character that cannot be encoded fails closed with
 * [UnsupportedExportTextException] (no replacement, no embedded font). The document uses a temporary-file-only stream
 * cache (`IOUtils.createTempFileOnlyStreamCache()`), so memory stays bounded, and is closed in a `finally`, which
 * removes the temporary file. This writer fills no cell: a masked cell is drawn as the empty text it is.
 */
class PdfExportWriter {
    fun write(
        table: ExportTable,
        out: OutputStream,
    ) {
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        checkEncodable(font, table)
        val document = PDDocument(IOUtils.createTempFileOnlyStreamCache())
        try {
            Layout(document, font, table).draw()
            document.save(out)
        } finally {
            document.close()
        }
    }

    private fun checkEncodable(
        font: PDFont,
        table: ExportTable,
    ) {
        HEADER_LABELS.forEach { requireEncodable(font, it, 0, it) }
        headerValues(table).forEachIndexed { i, v -> requireEncodable(font, HEADER_LABELS[i], 0, v) }
        table.columns.forEach { requireEncodable(font, it, 0, it) }
        table.cells.forEachIndexed { r, cells ->
            cells.forEachIndexed { c, text ->
                requireEncodable(font, table.columns.getOrElse(c) { "column-$c" }, r + 1, text)
            }
        }
    }

    private fun requireEncodable(
        font: PDFont,
        column: String,
        row: Int,
        text: String,
    ) {
        try {
            font.encode(text)
        } catch (_: IllegalArgumentException) {
            throw UnsupportedExportTextException(column, row)
        }
    }

    private class Layout(
        private val document: PDDocument,
        private val font: PDFont,
        private val table: ExportTable,
    ) {
        private var stream: PDPageContentStream? = null
        private var y = 0f
        private val columnWidth = (PAGE_WIDTH - 2 * MARGIN) / table.columns.size

        fun draw() {
            try {
                newPage()
                headerValues(table).forEachIndexed { i, v ->
                    val lines = wrap("${HEADER_LABELS[i]}: $v", PAGE_WIDTH - 2 * MARGIN)
                    lines.forEach { line ->
                        ensureRoom(1)
                        text(line, MARGIN)
                        y -= LEADING
                    }
                }
                y -= LEADING
                columnNames()
                table.cells.forEach { row(it) }
            } finally {
                stream?.close()
            }
        }

        private fun columnNames() = block(table.columns.map { wrap(it, columnWidth) }, withNames = false)

        private fun row(cells: List<String>) = block(cells.map { wrap(it, columnWidth) }, withNames = true)

        /** Draws one row (or the column names) cell by cell; a row taller than a page continues on the next. */
        private fun block(
            cellLines: List<List<String>>,
            withNames: Boolean,
        ) {
            val total = cellLines.maxOfOrNull { it.size } ?: 1
            val capacity = linesPerPage()
            var from = 0
            do {
                val count = minOf(maxOf(total - from, 1), capacity)
                ensureRoom(count, withNames)
                cellLines.forEachIndexed { c, lines ->
                    lines.drop(from).take(count).forEachIndexed { i, line ->
                        text(line, MARGIN + c * columnWidth, y - i * LEADING)
                    }
                }
                y -= count * LEADING
                from += count
            } while (from < total)
        }

        private fun linesPerPage() = ((PAGE_HEIGHT - 2 * MARGIN) / LEADING).toInt() - NAME_ROOM

        private fun ensureRoom(
            lines: Int,
            withNames: Boolean = true,
        ) {
            if (y - lines * LEADING < MARGIN) {
                newPage()
                if (withNames) columnNames()
            }
        }

        private fun newPage() {
            stream?.close()
            val page = PDPage(PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width))
            document.addPage(page)
            stream = PDPageContentStream(document, page)
            y = PAGE_HEIGHT - MARGIN - LEADING
        }

        private fun text(
            line: String,
            x: Float,
            at: Float = y,
        ) {
            if (line.isEmpty()) return
            val s = stream!!
            s.beginText()
            s.setFont(font, FONT_SIZE)
            s.newLineAtOffset(x, at)
            s.showText(line)
            s.endText()
        }

        /** Splits [text] into lines no wider than [width]; every character is kept. */
        private fun wrap(
            text: String,
            width: Float,
        ): List<String> {
            if (text.isEmpty()) return listOf("")
            val lines = mutableListOf<String>()
            val current = StringBuilder()
            text.forEach { ch ->
                val candidate = current.toString() + ch
                if (current.isNotEmpty() && widthOf(candidate) > width) {
                    lines += current.toString()
                    current.setLength(0)
                }
                current.append(ch)
            }
            lines += current.toString()
            return lines
        }

        private fun widthOf(s: String) = font.getStringWidth(s) / 1000f * FONT_SIZE
    }

    companion object {
        const val FONT_SIZE = 7f
        private const val LEADING = 8.5f
        private const val MARGIN = 20f
        private const val NAME_ROOM = 2
        private val PAGE_WIDTH = PDRectangle.A4.height
        private val PAGE_HEIGHT = PDRectangle.A4.width
        private val HEADER_LABELS =
            listOf(
                "entityRef",
                "gstn",
                "periodRef",
                "generatedAt",
                "generatedBy",
                "filter",
                "rowCount",
                "totalTaxableAmount",
                "totalGstAmount",
            )

        private fun headerValues(table: ExportTable): List<String> {
            val h = table.header
            return listOf(
                h.entityRef,
                h.gstn,
                h.periodRef,
                h.generatedAt.toString(),
                h.generatedBy,
                h.filter,
                table.rowCount.toString(),
                table.totalTaxableAmount.toPlainString(),
                table.totalGstAmount.toPlainString(),
            )
        }
    }
}
