package com.dynokore.gst.reconciliation.application.workpaper.export

import com.dynokore.gst.reconciliation.application.run.RunItems
import com.dynokore.gst.reconciliation.application.workpaper.GetWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.RunAnnotations
import com.dynokore.gst.reconciliation.application.workpaper.StoredAnnotation
import com.dynokore.gst.reconciliation.application.workpaper.WorkPaperBox
import com.dynokore.gst.reconciliation.application.workpaper.WorkPaperView
import com.dynokore.gst.reconciliation.domain.run.ReconcilingItem
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.ZoneOffset

sealed interface ExportOutcome {
    data class Exported(
        val rendered: RenderedExport,
    ) : ExportOutcome

    data object RunNotFound : ExportOutcome

    data object RelaxedControlsNotCurrent : ExportOutcome
}

/**
 * exportWorkPaper (INV-REQ-982): a read built per request from the work-paper view and the stored items; it stores
 * nothing and recomputes no item. The relaxed controls come from the policy replica; when it is not current the
 * export is refused, never an empty list. An input the export model refuses fails closed (NIST SP 800-53 SI-10).
 */
class ExportWorkPaper(
    private val view: GetWorkPaper,
    private val annotations: RunAnnotations,
    private val items: RunItems,
    private val relaxed: RelaxedControlsSource,
    private val renderer: WorkPaperRenderer,
    private val clock: Clock,
) {
    fun execute(
        tenant: String,
        runId: String,
        format: ExportFormat,
    ): ExportOutcome {
        val paper = view.execute(tenant, runId)
        val controls = paper?.let { relaxedControls(tenant, it.header.entityRef) }
        return when {
            paper == null -> ExportOutcome.RunNotFound
            controls == null -> ExportOutcome.RelaxedControlsNotCurrent
            else -> ExportOutcome.Exported(renderer.render(model(tenant, runId, paper, controls), format))
        }
    }

    private fun relaxedControls(
        tenant: String,
        entityRef: String,
    ): List<ExportRelaxedControl>? {
        val today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate()
        return try {
            relaxed.relaxed(tenant, entityRef, today)
        } catch (_: RelaxedControlsUnavailable) {
            null
        }
    }

    private fun model(
        tenant: String,
        runId: String,
        paper: WorkPaperView,
        controls: List<ExportRelaxedControl>,
    ): WorkPaperExport {
        val stored = items.items(tenant, runId)
        val held = paper.boxes.map { it.box }.toSet()
        stored.firstOrNull { it.box !in held }?.let {
            throw IllegalArgumentException("a stored item belongs to box ${it.box}, which the work paper does not hold")
        }
        val latest = annotations.all(tenant, runId).associateBy { it.annotation.box }
        return WorkPaperExport(
            runId = paper.runId,
            entityRef = paper.header.entityRef,
            periodRef = paper.header.periodRef,
            preparedAt = paper.header.preparedAt.toString(),
            returnVersion = paper.header.returnVersion,
            returnForm = paper.header.returnForm,
            boxes = paper.boxes.map { box(it, stored.filter { i -> i.box == it.box }, latest[it.box]) },
            unexplainedResidual = paper.unexplainedResidual,
            relaxedControls = controls,
            attestation = paper.attestation?.let { ExportAttestation(it.attestedBy, it.attestedAt.toString()) },
            unmappedLines =
                paper.unmappedLines.map {
                    ExportUnmappedLine(
                        it.docUuid,
                        it.version,
                        it.lineNumber,
                        it.taxCode,
                    )
                },
        )
    }

    private fun box(
        b: WorkPaperBox,
        own: List<ReconcilingItem>,
        annotation: StoredAnnotation?,
    ) = ExportBox(
        box = b.box,
        submittedAmount = b.submittedAmount,
        filedAmount = b.filedAmount,
        variance = b.variance,
        varianceClass = b.varianceClass,
        supportingDocuments =
            (own.map { it.docUuid } + annotation?.annotation?.references.orEmpty())
                .distinct()
                .sorted(),
        items =
            own.map {
                ExportReconcilingItem(
                    it.docUuid,
                    it.version,
                    it.lineNumber,
                    money(it.amount),
                    it.evidenceRefs,
                )
            },
    )

    private fun money(value: BigDecimal): String = value.setScale(2, RoundingMode.HALF_UP).toPlainString()
}
