package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.document.DocumentState
import com.dynokore.gst.canonical.domain.document.NewVersion
import java.util.UUID

/**
 * The owned facts of the document row of one record. Each value comes from its owner; none is invented here.
 * [prescribedPeriodRef] is empty when no period is assigned; [appliedPackVersions] are the packs rules-svc applied.
 */
@Suppress("LongParameterList") // one member per column of the document row, as the store port names them
data class DocumentRowFacts(
    val entityId: UUID,
    val businessUnitId: UUID?,
    val documentTypeCode: Short,
    val issuerPartyKey: String,
    val fiscalYear: Short,
    val internalReference: String?,
    val prescribedPeriodId: UUID?,
    val state: DocumentState,
    val prescribedPeriodRef: String,
    val appliedPackVersions: List<AppliedPackFact>,
    val version: NewVersion,
)

/**
 * Answers the row facts of a record. A null answer means no row exists yet for this record: a document whose type
 * code could not be derived (held for an unavailable rules-svc) gets no row until its release; nothing is persisted
 * as an unknown value.
 */
fun interface DocumentRowFactsSource {
    fun factsOf(
        command: RecordDocumentCommand,
        docUuid: UUID,
        version: Int,
        result: String,
    ): DocumentRowFacts?
}
