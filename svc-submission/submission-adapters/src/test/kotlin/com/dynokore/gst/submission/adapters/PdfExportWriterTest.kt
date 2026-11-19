package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.export.PdfExportWriter
import com.dynokore.gst.submission.adapters.export.UnsupportedExportTextException
import com.dynokore.gst.submission.domain.listing.ExportHeader
import com.dynokore.gst.submission.domain.listing.ExportTable
import com.dynokore.gst.submission.domain.listing.SubmissionExport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.name

class PdfExportWriterTest {
    private val header =
        ExportHeader(
            "E-1",
            "M12345678X",
            "2026-Q3",
            Instant.parse("2026-11-19T01:02:03Z"),
            "user-7",
            "direction=OUTPUT",
        )

    private fun cellsOf(
        i: Int,
        name: String = "Buyer $i",
        number: String = "DOC-$i",
    ): List<String> =
        SubmissionExport.COLUMNS.map {
            when (it) {
                "counterpartyName" -> name
                "documentNumber" -> number
                "taxableAmount" -> "100.00"
                "gstAmount" -> "9.00"
                else -> "$it-$i"
            }
        }

    private fun tableOf(cells: List<List<String>>) =
        ExportTable(header, SubmissionExport.COLUMNS, cells, cells.size, BigDecimal("110.00"), BigDecimal("8.10"))

    private fun bytesOf(table: ExportTable): ByteArray =
        ByteArrayOutputStream().also { PdfExportWriter().write(table, it) }.toByteArray()

    private fun squash(s: String) = s.filter { !it.isWhitespace() }

    private fun pdfBackFiles(): Set<String> {
        val tmp = Path.of(System.getProperty("java.io.tmpdir"))
        return Files
            .list(tmp)
            .use { s -> s.map { it.name }.toList() }
            .filter { it.contains("PDFBox") }
            .toSet()
    }

    private fun textOf(bytes: ByteArray) = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }

    @Test
    fun `INV-AC-16347 the PDF holds the header values and every cell text in row order`() {
        val table = tableOf((1..3).map { cellsOf(it) })
        val text = squash(textOf(bytesOf(table)))
        listOf("E-1", "M12345678X", "2026-Q3", "2026-11-19T01:02:03Z", "user-7", "direction=OUTPUT", "110.00", "8.10")
            .forEach { text shouldContain squash(it) }
        var from = 0
        table.cells.flatten().forEach { cell ->
            val at = text.indexOf(squash(cell), from)
            (at >= 0) shouldBe true
            from = at
        }
    }

    @Test
    fun `INV-AC-16347 a table of 400 rows gives more than one page and the column names appear once per page`() {
        val table = tableOf((1..400).map { cellsOf(it) })
        val names = squash(SubmissionExport.COLUMNS.joinToString(" "))
        Loader.loadPDF(bytesOf(table)).use { doc ->
            (doc.numberOfPages > 1) shouldBe true
            (1..doc.numberOfPages).forEach { p ->
                val stripper =
                    PDFTextStripper().also {
                        it.startPage = p
                        it.endPage = p
                    }
                val text = squash(stripper.getText(doc))
                Regex(Regex.escape(names)).findAll(text).count() shouldBe 1
            }
        }
    }

    @Test
    fun `INV-AC-16347 a 200-character document number is present in full`() {
        val number = (1..200).map { ('A' + it % 26) }.joinToString("")
        val text = squash(textOf(bytesOf(tableOf(listOf(cellsOf(1, number = number))))))
        text shouldContain number
    }

    @Test
    fun `INV-AC-16347 a character outside WinAnsi throws naming column and row and writes zero bytes`() {
        val table = tableOf(listOf(cellsOf(1), cellsOf(2, name = "中Secret")))
        val sink = CountingStream()
        val e = shouldThrow<UnsupportedExportTextException> { PdfExportWriter().write(table, sink) }
        e.column shouldBe "counterpartyName"
        e.row shouldBe 2
        sink.count shouldBe 0
        (e.message ?: "") shouldNotContain "Secret"
        (e.message ?: "") shouldContain "counterpartyName"
    }

    @Test
    fun `INV-AC-16347 no PDFBox temporary file remains after a write or after a failing stream`() {
        val before = pdfBackFiles()
        bytesOf(tableOf((1..50).map { cellsOf(it) }))
        pdfBackFiles() shouldBe before
        val failing =
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("sink closed")
            }
        shouldThrow<IOException> { PdfExportWriter().write(tableOf(listOf(cellsOf(1))), failing) }
        pdfBackFiles() shouldBe before
    }

    private class CountingStream : OutputStream() {
        var count = 0

        override fun write(b: Int) {
            count++
        }
    }
}
