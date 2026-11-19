package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.export.XlsxExportWriter
import com.dynokore.gst.submission.domain.listing.ExportHeader
import com.dynokore.gst.submission.domain.listing.ExportTable
import com.dynokore.gst.submission.domain.listing.SubmissionExport
import io.kotest.matchers.shouldBe
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.name

class XlsxExportWriterTest {
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
        taxable: String = "100.00",
        gst: String = "9.00",
    ): List<String> =
        SubmissionExport.COLUMNS.map {
            when (it) {
                "counterpartyName" -> name
                "taxableAmount" -> taxable
                "gstAmount" -> gst
                else -> "$it-$i"
            }
        }

    private fun tableOf(cells: List<List<String>>) =
        ExportTable(header, SubmissionExport.COLUMNS, cells, cells.size, BigDecimal("110.00"), BigDecimal("8.10"))

    private fun bytesOf(table: ExportTable): ByteArray =
        ByteArrayOutputStream().also { XlsxExportWriter().write(table, it) }.toByteArray()

    private fun sxssfFiles(): Set<String> =
        Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { s ->
            s
                .map { it.name }
                .filter { it.startsWith("poi-sxssf-sheet") }
                .toList()
                .toSet()
        }

    private fun assertBody(
        sheet: Sheet,
        table: ExportTable,
    ) {
        val money = setOf(9, 10)
        table.cells.forEachIndexed { r, expected ->
            val row = sheet.getRow(4 + r)
            row.physicalNumberOfCells shouldBe expected.size
            expected.forEachIndexed { c, text ->
                val cell = row.getCell(c)
                if (c in money) {
                    cell.cellType shouldBe CellType.NUMERIC
                    cell.cellStyle.dataFormatString shouldBe "0.00"
                    BigDecimal.valueOf(cell.numericCellValue).compareTo(BigDecimal(text)) shouldBe 0
                } else {
                    cell.cellType shouldBe CellType.STRING
                    cell.stringCellValue shouldBe text
                }
            }
        }
    }

    private fun assertHeaderBlock(sheet: Sheet) {
        val labels = sheet.getRow(0).map { it.stringCellValue }
        labels shouldBe
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
        sheet.getRow(1).map { it.stringCellValue } shouldBe
            listOf(
                "E-1",
                "M12345678X",
                "2026-Q3",
                "2026-11-19T01:02:03Z",
                "user-7",
                "direction=OUTPUT",
                "3",
                "110.00",
                "8.10",
            )
    }

    @Test
    fun `INV-AC-16345 the workbook holds the header block, the column names and one row per table row`() {
        val table =
            tableOf(
                listOf(
                    cellsOf(1),
                    cellsOf(2, taxable = "-90.00", gst = "-8.10"),
                    cellsOf(3, taxable = "100.10", gst = "9.01"),
                ),
            )
        XSSFWorkbook(ByteArrayInputStream(bytesOf(table))).use { wb ->
            wb.numberOfSheets shouldBe 1
            val sheet = wb.getSheet("Submissions")
            assertHeaderBlock(sheet)
            (sheet.getRow(2)?.physicalNumberOfCells ?: 0) shouldBe 0
            sheet.getRow(3).map { it.stringCellValue } shouldBe SubmissionExport.COLUMNS
            sheet.lastRowNum shouldBe 6
            assertBody(sheet, table)
        }
    }

    @Test
    fun `INV-AC-16345 a formula-looking counterparty name is a string cell with that exact text`() {
        val name = "=HYPERLINK(\"x\")"
        val bytes = bytesOf(tableOf(listOf(cellsOf(1, name = name))))
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            val cell = wb.getSheet("Submissions").getRow(4).getCell(6)
            cell.cellType shouldBe CellType.STRING
            cell.stringCellValue shouldBe name
        }
    }

    @Test
    fun `INV-AC-16345 a masked money cell stays an empty string cell`() {
        val bytes = bytesOf(tableOf(listOf(cellsOf(1, name = "", taxable = "", gst = ""))))
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            val row = wb.getSheet("Submissions").getRow(4)
            row.getCell(9).cellType shouldBe CellType.STRING
            row.getCell(9).stringCellValue shouldBe ""
            row.getCell(10).stringCellValue shouldBe ""
        }
    }

    @Test
    fun `INV-AC-16345 a table of 5000 rows is written and read back with the same row count`() {
        val table = tableOf((1..5000).map { cellsOf(it) })
        XSSFWorkbook(ByteArrayInputStream(bytesOf(table))).use { wb ->
            wb.getSheet("Submissions").lastRowNum - 3 shouldBe 5000
        }
    }

    @Test
    fun `INV-AC-16345 no poi temporary file remains after a write, also when the stream throws`() {
        val before = sxssfFiles()
        bytesOf(tableOf((1..500).map { cellsOf(it) }))
        sxssfFiles() shouldBe before
        val failing =
            object : OutputStream() {
                override fun write(b: Int): Unit = throw IOException("sink closed")
            }
        val table = tableOf((1..500).map { cellsOf(it) })
        val thrown = runCatching { XlsxExportWriter().write(table, failing) }.exceptionOrNull()
        (thrown is IOException) shouldBe true
        sxssfFiles() shouldBe before
    }

    @Test
    fun `INV-AC-16345 the streaming row window is 100`() {
        XlsxExportWriter.ROW_WINDOW shouldBe 100
    }
}
