package com.dynokore.gst.submission.adapters.export

import com.dynokore.gst.submission.domain.listing.ExportTable
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import java.io.OutputStream
import java.math.BigDecimal

/**
 * Writes the format-neutral [ExportTable] as an XLSX stream (INV-REQ-978, INV-AC-16345). The workbook is an
 * `SXSSFWorkbook` that keeps at most [ROW_WINDOW] rows of the sheet in memory. Every cell is a string cell (never a
 * formula, so a counterparty name such as `=HYPERLINK("x")` stays text) except the money columns, which are numeric
 * cells with the format `0.00`. A blank money cell (a masked fact) stays an empty string cell: this writer fills
 * nothing. The workbook is disposed and closed in a `finally`, so POI's temporary files are removed.
 */
class XlsxExportWriter {
    fun write(
        table: ExportTable,
        out: OutputStream,
    ) {
        val workbook = SXSSFWorkbook(ROW_WINDOW)
        try {
            fill(workbook, table)
            workbook.write(out)
        } finally {
            try {
                @Suppress("DEPRECATION") // POI 6 deprecates dispose(); the temp-file removal is asserted by a test.
                workbook.dispose()
            } finally {
                workbook.close()
            }
        }
    }

    private fun fill(
        workbook: SXSSFWorkbook,
        table: ExportTable,
    ) {
        val moneyStyle =
            workbook.createCellStyle().also {
                it.dataFormat = workbook.createDataFormat().getFormat(MONEY_FORMAT)
            }
        val sheet = workbook.createSheet(SHEET_NAME)
        val h = table.header
        textRow(sheet.createRow(0), HEADER_LABELS)
        textRow(
            sheet.createRow(1),
            listOf(
                h.entityRef,
                h.gstn,
                h.periodRef,
                h.generatedAt.toString(),
                h.generatedBy,
                h.filter,
                table.rowCount.toString(),
                table.totalTaxableAmount.toPlainString(),
                table.totalGstAmount.toPlainString(),
            ),
        )
        sheet.createRow(2)
        textRow(sheet.createRow(COLUMNS_ROW), table.columns)
        val moneyColumns = MONEY_COLUMNS.map { table.columns.indexOf(it) }.filter { it >= 0 }.toSet()
        table.cells.forEachIndexed { r, cells ->
            val row = sheet.createRow(COLUMNS_ROW + 1 + r)
            cells.forEachIndexed { c, text ->
                val cell = row.createCell(c)
                if (c in moneyColumns && text.isNotEmpty()) {
                    cell.setCellValue(BigDecimal(text).toDouble())
                    cell.cellStyle = moneyStyle
                } else {
                    cell.setCellValue(text)
                }
            }
        }
    }

    private fun textRow(
        row: Row,
        values: List<String>,
    ) {
        values.forEachIndexed { i, v -> row.createCell(i).setCellValue(v) }
    }

    companion object {
        /** At most this many rows of a sheet stay in memory. */
        const val ROW_WINDOW = 100
        const val SHEET_NAME = "Submissions"
        const val MONEY_FORMAT = "0.00"
        private const val COLUMNS_ROW = 3
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
        private val MONEY_COLUMNS = listOf("taxableAmount", "gstAmount")
    }
}
