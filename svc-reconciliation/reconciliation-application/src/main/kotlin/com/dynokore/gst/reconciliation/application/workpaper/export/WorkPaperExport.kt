package com.dynokore.gst.reconciliation.application.workpaper.export

import com.dynokore.gst.reconciliation.application.workpaper.WorkPaperVarianceShare

/** Output formats of the work-paper export (contract operation exportWorkPaper). */
enum class ExportFormat { PDF, SPREADSHEET }

private val MONEY = Regex("^-?\\d{1,17}(\\.\\d{1,2})?$")

private fun requireMoney(
    field: String,
    value: String,
) {
    require(MONEY.matches(value)) { "$field is not a money decimal string: '$value'" }
}

private fun requireText(
    field: String,
    value: String,
) {
    require(value.isNotBlank()) { "$field must not be blank" }
}

data class ExportReconcilingItem(
    val docUuid: String,
    val version: Int,
    val lineNumber: Int,
    val amount: String,
    val evidenceRefs: List<String>,
) {
    init {
        requireText("docUuid", docUuid)
        require(version >= 1) { "version must be at least 1: $version" }
        require(lineNumber >= 1) { "lineNumber must be at least 1: $lineNumber" }
        requireMoney("item amount", amount)
        require(evidenceRefs.isNotEmpty()) { "evidenceRefs must not be empty" }
        require(evidenceRefs.none { it.isBlank() }) { "evidenceRefs must not hold a blank reference" }
    }
}

data class ExportBox(
    val box: String,
    val submittedAmount: String,
    val filedAmount: String,
    val variance: String,
    val varianceClasses: List<WorkPaperVarianceShare>,
    val supportingDocuments: List<String>,
    val items: List<ExportReconcilingItem>,
) {
    init {
        requireText("box", box)
        requireMoney("submittedAmount", submittedAmount)
        requireMoney("filedAmount", filedAmount)
        requireMoney("variance", variance)
        require(supportingDocuments.none { it.isBlank() }) { "supportingDocuments must not hold a blank reference" }
        items.forEach {
            require(it.docUuid in supportingDocuments) {
                "item document ${it.docUuid} is not in the supportingDocuments of box $box"
            }
        }
    }
}

data class ExportRelaxedControl(
    val code: String,
    val description: String,
)

data class ExportAttestation(
    val attestedBy: String,
    val attestedAt: String,
)

data class ExportUnmappedLine(
    val docUuid: String,
    val version: Int,
    val lineNumber: Int,
    val taxCode: String,
) {
    init {
        requireText("docUuid", docUuid)
        require(version >= 1) { "version must be at least 1: $version" }
        require(lineNumber >= 1) { "lineNumber must be at least 1: $lineNumber" }
        requireText("taxCode", taxCode)
    }
}

data class WorkPaperExport(
    val runId: String,
    val entityRef: String,
    val periodRef: String,
    val preparedAt: String,
    val returnVersion: Int?,
    val returnForm: String?,
    val boxes: List<ExportBox>,
    val unexplainedResidual: String,
    val relaxedControls: List<ExportRelaxedControl>,
    val attestation: ExportAttestation?,
    val unmappedLines: List<ExportUnmappedLine>,
) {
    init {
        requireText("runId", runId)
        requireText("entityRef", entityRef)
        requireText("periodRef", periodRef)
        requireMoney("unexplainedResidual", unexplainedResidual)
        val names = boxes.map { it.box }
        require(names.size == names.toSet().size) { "duplicate box name in boxes" }
    }
}
