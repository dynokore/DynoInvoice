package com.dynokore.gst.reconciliation.application.workpaper.export

class RenderedExport(
    val contentType: String,
    val bytes: ByteArray,
)

/** Port: renders the export model; implementations read the items only through [ExportRows.of]. */
fun interface WorkPaperRenderer {
    fun render(
        export: WorkPaperExport,
        format: ExportFormat,
    ): RenderedExport
}

/** The two media types of the contract response of exportWorkPaper. */
object ExportContentTypes {
    const val PDF = "application/pdf"
    const val SPREADSHEET = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
}
