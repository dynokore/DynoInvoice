package com.dynokore.gst.reconciliation.adapters.export

import com.dynokore.gst.reconciliation.application.workpaper.export.ExportContentTypes
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportFormat
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportRows
import com.dynokore.gst.reconciliation.application.workpaper.export.RenderedExport
import com.dynokore.gst.reconciliation.application.workpaper.export.WorkPaperExport
import com.dynokore.gst.reconciliation.application.workpaper.export.WorkPaperRenderer
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import java.io.ByteArrayOutputStream

/**
 * Spreadsheet writer of the work-paper export (INV-REQ-982). Streaming workbook (bounded memory); every cell is a
 * text cell, so amounts keep their exact decimal text and no value is ever evaluated as a formula. Items are read
 * only through [ExportRows.of].
 */
class XlsxWorkPaperRenderer : WorkPaperRenderer {
    override fun render(
        export: WorkPaperExport,
        format: ExportFormat,
    ): RenderedExport {
        require(format == ExportFormat.SPREADSHEET) { "XlsxWorkPaperRenderer renders only SPREADSHEET, not $format" }
        val workbook = SXSSFWorkbook(ROW_WINDOW)
        try {
            writeSummary(workbook.createSheet("Summary"), export)
            writeBoxes(workbook.createSheet("Boxes"), export)
            writeItems(workbook.createSheet("Items"), export)
            writeRelaxed(workbook.createSheet("RelaxedControls"), export)
            writeUnmapped(workbook.createSheet("UnmappedLines"), export)
            val out = ByteArrayOutputStream()
            workbook.write(out)
            return RenderedExport(ExportContentTypes.SPREADSHEET, out.toByteArray())
        } finally {
            @Suppress("DEPRECATION") // dispose() deletes the temp files; close() below is the non-deprecated twin
            workbook.dispose()
            workbook.close()
        }
    }

    private fun writeSummary(
        sheet: Sheet,
        export: WorkPaperExport,
    ) {
        var n = 0

        fun line(vararg cells: String) = row(sheet, n++, *cells)
        line("run", export.runId)
        line("entity", export.entityRef)
        line("period", export.periodRef)
        line("prepared at", export.preparedAt)
        line("return version", export.returnVersion?.toString().orEmpty())
        line("return form", export.returnForm.orEmpty())
        line("unexplained residual", export.unexplainedResidual)
        val attestation = export.attestation
        if (attestation == null) {
            line("attestation", "not attested")
        } else {
            line("attested by", attestation.attestedBy)
            line("attested at", attestation.attestedAt)
        }
    }

    private fun writeBoxes(
        sheet: Sheet,
        export: WorkPaperExport,
    ) {
        row(sheet, 0, "box", "submitted", "filed", "variance", "variance class", "supporting documents")
        export.boxes.forEachIndexed { i, b ->
            row(
                sheet,
                i + 1,
                b.box,
                b.submittedAmount,
                b.filedAmount,
                b.variance,
                b.varianceClass.orEmpty(),
                b.supportingDocuments.joinToString("\n"),
            )
        }
    }

    private fun writeItems(
        sheet: Sheet,
        export: WorkPaperExport,
    ) {
        row(sheet, 0, "box", "document", "version", "line", "amount", "evidence")
        ExportRows.of(export).forEachIndexed { i, r ->
            row(
                sheet,
                i + 1,
                r.box,
                r.docUuid,
                r.version.toString(),
                r.lineNumber.toString(),
                r.amount,
                r.evidenceRefs.joinToString("\n"),
            )
        }
    }

    private fun writeRelaxed(
        sheet: Sheet,
        export: WorkPaperExport,
    ) {
        row(sheet, 0, "code", "description")
        export.relaxedControls.forEachIndexed { i, c -> row(sheet, i + 1, c.code, c.description) }
    }

    private fun writeUnmapped(
        sheet: Sheet,
        export: WorkPaperExport,
    ) {
        row(sheet, 0, "document", "version", "line", "tax code")
        export.unmappedLines.forEachIndexed { i, u ->
            row(sheet, i + 1, u.docUuid, u.version.toString(), u.lineNumber.toString(), u.taxCode)
        }
    }

    /** The one cell writer: the string setter only. */
    private fun row(
        sheet: Sheet,
        index: Int,
        vararg cells: String,
    ) {
        val row = sheet.createRow(index)
        cells.forEachIndexed { c, value -> row.createCell(c).setCellValue(value) }
    }

    private companion object {
        const val ROW_WINDOW = 50
    }
}
