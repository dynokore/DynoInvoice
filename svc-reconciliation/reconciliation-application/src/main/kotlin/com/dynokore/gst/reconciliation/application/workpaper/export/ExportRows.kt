package com.dynokore.gst.reconciliation.application.workpaper.export

/** One reconciling-item row; the single list both export formats read. */
data class ExportRow(
    val box: String,
    val docUuid: String,
    val version: Int,
    val lineNumber: Int,
    val amount: String,
    val evidenceRefs: List<String>,
)

object ExportRows {
    private val itemOrder =
        compareBy<ExportReconcilingItem>({ it.docUuid }, { it.version }, { it.lineNumber })

    /** Boxes in the listed order; items per Box by docUuid, version, line number; evidence kept as given. */
    fun of(export: WorkPaperExport): List<ExportRow> =
        export.boxes.flatMap { box ->
            box.items.sortedWith(itemOrder).map {
                ExportRow(box.box, it.docUuid, it.version, it.lineNumber, it.amount, it.evidenceRefs)
            }
        }
}
