package com.dynokore.gst.rules.application

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.domain.classification.Classification
import com.dynokore.gst.rules.domain.classification.ClassificationTable
import com.dynokore.gst.rules.domain.classification.ClassifyInput
import com.dynokore.gst.rules.domain.classification.DocumentClassifier
import com.dynokore.gst.rules.domain.classification.JournalClassification
import com.dynokore.gst.rules.domain.classification.JournalPosting
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.tenancykernel.TenantId

/** Read port for the classification table of the scope pack in force on the date; null when none is effective. */
fun interface ClassificationTablePort {
    fun tableAt(
        tenant: TenantId?,
        date: TransactionDate,
    ): ClassificationTable?
}

data class ClassifyCommand(
    val tenant: TenantId?,
    val date: TransactionDate,
    val input: ClassifyInput,
)

sealed interface ClassifyResult {
    data class Classified(
        val classification: Classification,
    ) : ClassifyResult

    /** No scope pack version is effective on the date: RUL-002, nothing is classified against another one. */
    data class Refused(
        val code: ErrorCode,
        val packType: PackType,
        val date: TransactionDate,
    ) : ClassifyResult
}

/**
 * Classification by function (INV-REQ-371) and adjustment mapping (INV-REQ-379, INV-REQ-658 to 660) under the scope
 * pack of the document's date. It reads no clock and submits nothing: a debit note carries the solution-extracted flag
 * (INV-REQ-373) and a NOT_A_BILL result is no submission trigger.
 */
class ClassifyDocument(
    private val tables: ClassificationTablePort,
) {
    fun classify(command: ClassifyCommand): ClassifyResult {
        val table =
            tables.tableAt(command.tenant, command.date)
                ?: return ClassifyResult.Refused(ErrorCode.RUL_002, PackTypes.SCOPE, command.date)
        return ClassifyResult.Classified(DocumentClassifier.classify(command.input, table))
    }

    /** A posting with no source document (INV-REQ-372): rules decide, the adapter does not (INV-REQ-800). */
    fun classifyJournal(posting: JournalPosting): JournalClassification = DocumentClassifier.classifyJournal(posting)
}
