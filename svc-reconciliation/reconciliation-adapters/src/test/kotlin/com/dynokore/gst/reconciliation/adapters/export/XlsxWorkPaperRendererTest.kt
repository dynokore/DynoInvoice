package com.dynokore.gst.reconciliation.adapters.export

import com.dynokore.gst.reconciliation.application.workpaper.export.ExportAttestation
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportBox
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportContentTypes
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportFormat
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportReconcilingItem
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportRelaxedControl
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportRows
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportUnmappedLine
import com.dynokore.gst.reconciliation.application.workpaper.export.WorkPaperExport
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.util.DefaultTempFileCreationStrategy
import org.apache.poi.util.TempFile
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries

class XlsxWorkPaperRendererTest {
    private val renderer = XlsxWorkPaperRenderer()

    private fun fixture(attestation: ExportAttestation? = ExportAttestation("user-7", "2026-11-18T10:00:00Z")) =
        WorkPaperExport(
            runId = "run-1",
            entityRef = "entity-1",
            periodRef = "2026-Q3",
            preparedAt = "2026-11-18T09:00:00Z",
            returnVersion = 2,
            returnForm = "F5",
            boxes =
                listOf(
                    ExportBox(
                        "5",
                        "100.00",
                        "90.00",
                        "10.00",
                        "MATERIAL",
                        listOf("doc-b", "doc-a"),
                        listOf(
                            ExportReconcilingItem("doc-b", 1, 2, "4.50", listOf("ev-2", "=SUM(A1:A2)")),
                            ExportReconcilingItem("doc-a", 1, 1, "5.50", listOf("ev-1")),
                        ),
                    ),
                    ExportBox(
                        "6",
                        "20.00",
                        "20.00",
                        "0.00",
                        null,
                        listOf("doc-c"),
                        listOf(ExportReconcilingItem("doc-c", 3, 1, "-1.25", listOf("ev-3"))),
                    ),
                ),
            unexplainedResidual = "0.00",
            relaxedControls = listOf(ExportRelaxedControl("RC-1", "Relaxed control one")),
            attestation = attestation,
            unmappedLines = listOf(ExportUnmappedLine("doc-z", 1, 4, "XX")),
        )

    private fun read(bytes: ByteArray) = XSSFWorkbook(ByteArrayInputStream(bytes))

    private fun rows(sheet: Sheet): List<List<String>> =
        (0..sheet.lastRowNum).map { r -> sheet.getRow(r).map { it.stringCellValue } }

    @Test
    fun `INV-AC-24329 content type is the spreadsheet media type`() {
        assertEquals(ExportContentTypes.SPREADSHEET, renderer.render(fixture(), ExportFormat.SPREADSHEET).contentType)
    }

    @Test
    fun `INV-AC-24329 items sheet equals the single row list in order`() {
        val export = fixture()
        val expected = ExportRows.of(export)
        read(renderer.render(export, ExportFormat.SPREADSHEET).bytes).use { wb ->
            val items = rows(wb.getSheet("Items"))
            assertEquals(listOf("box", "document", "version", "line", "amount", "evidence"), items[0])
            assertEquals(3, expected.size)
            assertEquals(expected.size, items.size - 1)
            expected.forEachIndexed { i, r ->
                assertEquals(
                    listOf(
                        r.box,
                        r.docUuid,
                        r.version.toString(),
                        r.lineNumber.toString(),
                        r.amount,
                        r.evidenceRefs.joinToString("\n"),
                    ),
                    items[i + 1],
                )
            }
        }
    }

    @Test
    fun `INV-AC-24329 every cell is a text cell and an equals sign stays text`() {
        read(renderer.render(fixture(), ExportFormat.SPREADSHEET).bytes).use { wb ->
            var formulaLike: Cell? = null
            wb.forEach { sheet ->
                sheet.forEach { row ->
                    row.forEach { cell ->
                        assertEquals(CellType.STRING, cell.cellType, "${sheet.sheetName}!${cell.address}")
                        if (cell.stringCellValue.contains("=")) formulaLike = cell
                    }
                }
            }
            assertEquals("ev-2\n=SUM(A1:A2)", wb.getSheet("Items").getRow(2).getCell(5).stringCellValue)
            assertEquals(CellType.STRING, wb.getSheet("Items").getRow(2).getCell(5).cellType)
            assertEquals("ev-2\n=SUM(A1:A2)", formulaLike?.stringCellValue)
            assertEquals(listOf("Summary", "Boxes", "Items", "RelaxedControls", "UnmappedLines"), wb.map { it.sheetName })
        }
    }

    @Test
    fun `INV-AC-24329 a cell that starts with an equals sign is a string cell`() {
        val export =
            fixture().copy(relaxedControls = listOf(ExportRelaxedControl("=1+1", "=cmd")))
        read(renderer.render(export, ExportFormat.SPREADSHEET).bytes).use { wb ->
            val cell = wb.getSheet("RelaxedControls").getRow(1).getCell(0)
            assertEquals(CellType.STRING, cell.cellType)
            assertEquals("=1+1", cell.stringCellValue)
        }
    }

    @Test
    fun `INV-AC-24329 boxes relaxed controls and unmapped lines equal the model`() {
        read(renderer.render(fixture(), ExportFormat.SPREADSHEET).bytes).use { wb ->
            assertEquals(
                listOf(
                    listOf("box", "submitted", "filed", "variance", "variance class", "supporting documents"),
                    listOf("5", "100.00", "90.00", "10.00", "MATERIAL", "doc-b\ndoc-a"),
                    listOf("6", "20.00", "20.00", "0.00", "", "doc-c"),
                ),
                rows(wb.getSheet("Boxes")),
            )
            assertEquals(
                listOf(listOf("code", "description"), listOf("RC-1", "Relaxed control one")),
                rows(wb.getSheet("RelaxedControls")),
            )
            assertEquals(
                listOf(listOf("document", "version", "line", "tax code"), listOf("doc-z", "1", "4", "XX")),
                rows(wb.getSheet("UnmappedLines")),
            )
        }
    }

    @Test
    fun `INV-AC-24329 summary carries the attestation or not attested`() {
        read(renderer.render(fixture(), ExportFormat.SPREADSHEET).bytes).use { wb ->
            assertEquals(
                listOf(
                    listOf("run", "run-1"),
                    listOf("entity", "entity-1"),
                    listOf("period", "2026-Q3"),
                    listOf("prepared at", "2026-11-18T09:00:00Z"),
                    listOf("return version", "2"),
                    listOf("return form", "F5"),
                    listOf("unexplained residual", "0.00"),
                    listOf("attested by", "user-7"),
                    listOf("attested at", "2026-11-18T10:00:00Z"),
                ),
                rows(wb.getSheet("Summary")),
            )
        }
        read(renderer.render(fixture(null), ExportFormat.SPREADSHEET).bytes).use { wb ->
            assertEquals(listOf("attestation", "not attested"), rows(wb.getSheet("Summary")).last())
        }
    }

    @Test
    fun `INV-AC-24329 the PDF format is refused and no bytes are returned`() {
        val e = assertThrows(IllegalArgumentException::class.java) { renderer.render(fixture(), ExportFormat.PDF) }
        assertEquals(true, e.message!!.contains("PDF"))
    }

    @Test
    fun `INV-AC-24329 two renders give equal item rows`() {
        val export = fixture()
        val a = read(renderer.render(export, ExportFormat.SPREADSHEET).bytes).use { rows(it.getSheet("Items")) }
        val b = read(renderer.render(export, ExportFormat.SPREADSHEET).bytes).use { rows(it.getSheet("Items")) }
        assertEquals(a, b)
    }

    @Test
    fun `INV-AC-24329 the temporary files of the workbook are deleted after render`(
        @TempDir dir: Path,
    ) {
        TempFile.setTempFileCreationStrategy(DefaultTempFileCreationStrategy(dir.toFile()))
        try {
            renderer.render(fixture(), ExportFormat.SPREADSHEET)
            val leftovers =
                Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
            assertEquals(emptyList<Path>(), leftovers)
            assertEquals(true, dir.listDirectoryEntries().size <= 1)
        } finally {
            TempFile.setTempFileCreationStrategy(DefaultTempFileCreationStrategy())
        }
    }
}
