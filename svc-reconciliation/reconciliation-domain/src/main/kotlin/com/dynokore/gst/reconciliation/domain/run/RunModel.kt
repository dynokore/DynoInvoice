package com.dynokore.gst.reconciliation.domain.run

import com.dynokore.gst.reconciliation.domain.boxes.Direction
import com.dynokore.gst.reconciliation.domain.boxes.UnmappedLine
import com.dynokore.gst.reconciliation.domain.sgd.NotReconcilableReason
import com.dynokore.gst.reconciliation.domain.sgd.SgdAllocation
import com.dynokore.gst.reconciliation.domain.variance.DocumentFacts
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass
import com.dynokore.platform.reconciliationengine.EvidenceRecord
import com.dynokore.platform.reconciliationengine.ReconciliationResult
import java.math.BigDecimal

/** The `boxes` of the current (highest recorded) return version, keyed by `GstReturnBox.box`. */
data class ReturnFigures(
    val version: Int,
    val boxes: Map<String, BigDecimal>,
)

/** One version of a document as the read model holds it, after the SGD allocation (INV-Q-7226). */
data class RunDocumentVersion(
    val version: Int,
    val acknowledged: Boolean,
    val facts: DocumentFacts,
    val direction: Direction,
    val allocation: SgdAllocation,
)

data class RunDocument(
    val docUuid: String,
    val versions: List<RunDocumentVersion>,
)

/** A version the allocation refuses: it contributes no amount (INV-Q-7239, INV-Q-7226). */
data class NotReconcilableDocument(
    val docUuid: String,
    val version: Int,
    val reason: NotReconcilableReason,
)

/** The amount of a never-acknowledged document in one Box; [reference] is the docUuid. */
data class DocumentEvidence(
    override val reference: String,
    override val amount: BigDecimal,
    val varianceClass: VarianceClass?,
) : EvidenceRecord

sealed interface RunOutcome {
    /** No return recorded for the period: no comparison exists (INV-REQ-657). */
    data class NoReturn(
        val code: String,
    ) : RunOutcome

    data class Completed(
        val returnVersion: Int,
        val result: ReconciliationResult,
        val unmappedLines: List<UnmappedLine>,
        val notReconcilable: List<NotReconcilableDocument>,
        val raisesPer003: Boolean,
    ) : RunOutcome

    companion object {
        const val PER_008 = "PER-008"
        const val PER_003 = "PER-003"
    }
}
