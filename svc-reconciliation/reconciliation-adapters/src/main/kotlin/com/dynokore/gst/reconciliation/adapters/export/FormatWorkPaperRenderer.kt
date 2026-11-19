package com.dynokore.gst.reconciliation.adapters.export

import com.dynokore.gst.reconciliation.application.workpaper.export.ExportFormat
import com.dynokore.gst.reconciliation.application.workpaper.export.RenderedExport
import com.dynokore.gst.reconciliation.application.workpaper.export.WorkPaperExport
import com.dynokore.gst.reconciliation.application.workpaper.export.WorkPaperRenderer

/**
 * The renderer that dispatches by format (INV-REQ-982): one export model, handed unchanged to the writer of the
 * requested format, so both formats itemise the same items. A format with no writer is refused, never answered by
 * another writer.
 */
class FormatWorkPaperRenderer internal constructor(
    private val renderers: Map<ExportFormat, WorkPaperRenderer>,
) : WorkPaperRenderer {
    constructor(spreadsheet: WorkPaperRenderer, pdf: WorkPaperRenderer) :
        this(mapOf(ExportFormat.SPREADSHEET to spreadsheet, ExportFormat.PDF to pdf))

    override fun render(
        export: WorkPaperExport,
        format: ExportFormat,
    ): RenderedExport {
        val renderer = renderers[format] ?: throw IllegalArgumentException("no renderer for format $format")
        return renderer.render(export, format)
    }
}
