package com.dynokore.gst.reconciliation.application.workpaper

import com.dynokore.gst.reconciliation.application.run.RunBox
import com.dynokore.gst.reconciliation.application.run.RunReader
import com.dynokore.gst.reconciliation.application.run.RunUnmappedLine
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

data class WorkPaperHeader(
    val entityRef: String,
    val periodRef: String,
    val preparedAt: Instant,
    val returnVersion: Int,
    val returnForm: String,
)

data class WorkPaperVarianceShare(
    val varianceClass: String,
    val amount: String,
)

data class WorkPaperBox(
    val box: String,
    val submittedAmount: String,
    val filedAmount: String,
    val variance: String,
    val varianceClasses: List<WorkPaperVarianceShare>,
    val unexplainedResidual: String,
    val references: List<String>,
)

data class WorkPaperView(
    val runId: String,
    val header: WorkPaperHeader,
    val boxes: List<WorkPaperBox>,
    val unexplainedResidual: String,
    val supportingDocuments: List<String>,
    val attestation: StoredAttestation?,
    val unmappedLines: List<RunUnmappedLine>,
)

/**
 * getWorkPaper (INV-REQ-633). The run store keeps no linked records per classified variance, so
 * supportingDocuments holds the references of the latest annotation of each Box only.
 */
class GetWorkPaper(
    private val runs: RunReader,
    private val annotations: RunAnnotations,
    private val attestations: RunAttestations,
) {
    fun execute(
        tenant: String,
        runId: String,
    ): WorkPaperView? {
        val snapshot = runs.find(tenant, runId) ?: return null
        val latest = annotations.all(tenant, runId).associateBy { it.annotation.box }
        val boxes =
            snapshot.boxes.map { b ->
                val annotation = latest[b.box]?.annotation
                val cls = annotation?.varianceClass
                val residual = if (cls != null) BigDecimal.ZERO else b.unexplainedResidual
                WorkPaperBox(
                    box = b.box,
                    submittedAmount = money(b.submittedAmount),
                    filedAmount = money(b.filedAmount),
                    variance = money(b.variance),
                    varianceClasses = classes(b, cls?.value),
                    unexplainedResidual = money(residual),
                    references = annotation?.references.orEmpty(),
                )
            }
        val total =
            snapshot.boxes.fold(BigDecimal.ZERO) { sum, b ->
                if (latest[b.box]?.annotation?.varianceClass != null) sum else sum + b.unexplainedResidual
            }
        val run = snapshot.run
        return WorkPaperView(
            runId = run.runId,
            header =
                WorkPaperHeader(
                    run.entityRef,
                    run.periodRef,
                    run.completedAt,
                    run.returnVersion,
                    run.returnForm,
                ),
            boxes = boxes,
            unexplainedResidual = money(total),
            supportingDocuments = boxes.flatMap { it.references }.distinct().sorted(),
            attestation = attestations.get(tenant, runId),
            unmappedLines = snapshot.unmappedLines,
        )
    }

    private fun classes(
        b: RunBox,
        assigned: String?,
    ): List<WorkPaperVarianceShare> {
        val shown = b.varianceClasses.map { WorkPaperVarianceShare(it.varianceClass, money(it.amount)) }
        if (assigned == null) return shown
        val sums = sortedMapOf<String, BigDecimal>()
        for (c in b.varianceClasses) {
            if (c.varianceClass != UNEXPLAINED) sums.merge(c.varianceClass, c.amount, BigDecimal::add)
        }
        sums.merge(assigned, b.unexplainedResidual, BigDecimal::add)
        return sums.map { (k, v) -> WorkPaperVarianceShare(k, money(v)) }
    }

    private fun money(value: BigDecimal): String = value.setScale(2, RoundingMode.HALF_UP).toPlainString()
}

private const val UNEXPLAINED = "UNEXPLAINED"
