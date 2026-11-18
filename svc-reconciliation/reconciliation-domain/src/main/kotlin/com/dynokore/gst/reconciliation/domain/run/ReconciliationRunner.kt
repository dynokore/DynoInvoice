package com.dynokore.gst.reconciliation.domain.run

import com.dynokore.gst.reconciliation.domain.boxes.BoxAggregator
import com.dynokore.gst.reconciliation.domain.boxes.DocumentLine
import com.dynokore.gst.reconciliation.domain.boxes.MappingEntry
import com.dynokore.gst.reconciliation.domain.boxes.UnmappedLine
import com.dynokore.gst.reconciliation.domain.boxes.VersionState
import com.dynokore.gst.reconciliation.domain.sgd.SgdAllocation
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass
import com.dynokore.gst.reconciliation.domain.variance.VarianceClasses
import com.dynokore.platform.reconciliationengine.Category
import com.dynokore.platform.reconciliationengine.ClassificationRule
import com.dynokore.platform.reconciliationengine.EvidenceRecord
import com.dynokore.platform.reconciliationengine.ReconciliationEngine
import com.dynokore.platform.reconciliationengine.ReconciliationResult

/** Runs a reconciliation for one (entity, period) over values only (INV-Q-7239 A). Pure: no I/O, no clock. */
object ReconciliationRunner {
    private val classes: List<VarianceClass> = VarianceClass.entries.filter { it != VarianceClass.UNEXPLAINED }

    internal val categories: Map<VarianceClass, Category> =
        mapOf(
            VarianceClass.TIMING_DIFFERENCE to "Timing difference",
            VarianceClass.NO_INVOICE_REQUIRED to "Supplies where invoices are not required",
            VarianceClass.INPUT_TAX_NOT_CLAIMED to "Input tax not claimed or not claimed in full",
            VarianceClass.ERROR_CORRECTED_VIA_F5 to "Error corrected via F5 rather than F7",
            VarianceClass.EXCLUDED_TXN to "Excluded transaction",
            VarianceClass.PRE_OBLIGATION to "Before the obligation start",
            VarianceClass.OUTSIDE_BY_RULE to "Outside submission by rule",
            VarianceClass.BUYER_REJECTED to "Not submitted: rejected by the buyer",
        ).mapValues { (cls, label) -> Category(cls.name, label) }

    private class Chosen(
        val docUuid: String,
        val version: Int,
        val data: RunDocumentVersion,
    )

    fun run(
        returnFigures: ReturnFigures?,
        entries: List<MappingEntry>,
        documents: List<RunDocument>,
    ): RunOutcome {
        if (returnFigures == null) return RunOutcome.NoReturn(RunOutcome.PER_008)
        requireDistinct(documents)
        val states =
            documents.flatMap { doc -> doc.versions.map { VersionState(doc.docUuid, it.version, it.acknowledged) } }
        val submitted = chosen(documents, BoxAggregator.submittedVersions(states))
        val unsubmitted = chosen(documents, BoxAggregator.unsubmittedCurrentVersions(states))

        val submittedAggregation = BoxAggregator.aggregate(entries, submitted.flatMap(::linesOf))
        val evidence = mutableMapOf<String, MutableList<EvidenceRecord>>()
        val docEvidence = mutableMapOf<Pair<String, String>, DocumentEvidence>()
        val unmapped = submittedAggregation.unmappedLines.toMutableList()
        for (c in unsubmitted) {
            val aggregation = BoxAggregator.aggregate(entries, linesOf(c))
            unmapped += aggregation.unmappedLines
            val varianceClass = VarianceClasses.derive(c.data.facts)
            aggregation.boxes
                .filterValues { it.signum() != 0 }
                .forEach { (box, amount) ->
                    val record = DocumentEvidence(c.docUuid, amount, varianceClass)
                    docEvidence[box to c.docUuid] = record
                    evidence.getOrPut(box) { mutableListOf() } += record
                }
        }
        val boxKeys = returnFigures.boxes.keys + submittedAggregation.boxes.keys + evidence.keys
        val result =
            ReconciliationEngine(categories.values.toSet(), rulesFor(boxKeys))
                .reconcile(returnFigures.boxes, submittedAggregation.boxes, evidence)
        return RunOutcome.Completed(
            returnVersion = returnFigures.version,
            result = result,
            unmappedLines = unmapped.sortedWith(lineOrder),
            notReconcilable = notReconcilableOf(submitted + unsubmitted),
            raisesPer003 = result.hasUnexplained,
            reconcilingItems = itemsOf(entries, unsubmitted, docEvidence, result),
        )
    }

    /** An item's references: the evidence reference of its document linked by a classified variance, else docUuid. */
    private fun itemsOf(
        entries: List<MappingEntry>,
        unsubmitted: List<Chosen>,
        docEvidence: Map<Pair<String, String>, DocumentEvidence>,
        result: ReconciliationResult,
    ): List<ReconcilingItem> {
        val linked =
            result.classifiedVariances.groupBy({ it.key }, { it.linkedRecords }).mapValues { (_, v) ->
                v.flatten().toSet()
            }
        val contributions =
            unsubmitted.flatMap { c ->
                BoxAggregator.contributions(entries, linesOf(c)).filter { it.amount.signum() != 0 }.map { c to it }
            }
        return contributions
            .map { (c, contribution) ->
                val record = docEvidence[contribution.box to c.docUuid]
                val refs = listOfNotNull(record?.reference?.takeIf { it in linked[contribution.box].orEmpty() })
                ReconcilingItem(
                    contribution.box,
                    contribution.docUuid,
                    contribution.version,
                    contribution.lineNumber,
                    contribution.amount,
                    refs.ifEmpty { listOf(c.docUuid) },
                )
            }.sortedWith(compareBy({ it.box }, { it.docUuid }, { it.version }, { it.lineNumber }))
    }

    private fun requireDistinct(documents: List<RunDocument>) {
        require(documents.map { it.docUuid }.toSet().size == documents.size) { "two documents have the same docUuid" }
        documents.forEach { doc ->
            require(
                doc.versions
                    .map { it.version }
                    .toSet()
                    .size == doc.versions.size,
            ) {
                "document ${doc.docUuid} lists a version twice"
            }
        }
    }

    private fun notReconcilableOf(chosen: List<Chosen>): List<NotReconcilableDocument> =
        chosen
            .mapNotNull { c ->
                (c.data.allocation as? SgdAllocation.NotReconcilable)?.let {
                    NotReconcilableDocument(c.docUuid, c.version, it.reason)
                }
            }.sortedWith(compareBy({ it.docUuid }, { it.version }))

    internal fun rulesFor(boxKeys: Set<String>): List<ClassificationRule> =
        boxKeys.flatMap { box ->
            classes.map { cls ->
                ClassificationRule(
                    category = categories.getValue(cls),
                    key = box,
                    matches = { record -> record is DocumentEvidence && record.varianceClass == cls },
                )
            }
        }

    private val lineOrder: Comparator<UnmappedLine> = compareBy({ it.docUuid }, { it.version }, { it.lineNumber })

    private fun chosen(
        documents: List<RunDocument>,
        versions: Map<String, Int>,
    ): List<Chosen> =
        documents.mapNotNull { doc ->
            versions[doc.docUuid]?.let { number ->
                Chosen(doc.docUuid, number, doc.versions.first { it.version == number })
            }
        }

    private fun linesOf(c: Chosen): List<DocumentLine> =
        when (val allocation = c.data.allocation) {
            is SgdAllocation.Allocated -> {
                allocation.lines.map { DocumentLine(c.docUuid, c.version, c.data.direction, it) }
            }

            is SgdAllocation.NotReconcilable -> {
                emptyList()
            }
        }
}
