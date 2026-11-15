package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.dating.DateBasis
import com.dynokore.gst.canonical.domain.dating.PeriodSpan
import java.time.LocalDate
import java.util.UUID

/** The prescribed-period spans of an entity (fed from PeriodRecordsChanged). */
fun interface PeriodSpans {
    fun spansOf(
        tenantId: UUID,
        entityRef: String,
    ): List<PeriodSpan>
}

/** The purchase date basis election of an entity in force on a date; null when none is recorded. */
fun interface PurchaseElections {
    fun electionOf(
        tenantId: UUID,
        entityRef: String,
        on: LocalDate,
    ): DateBasis?
}

/** The answer of the synchronous scope evaluation at rules-svc. */
sealed interface ScopeAnswer {
    data class Verdict(
        val scopeVerdict: String,
        val documentTypeCode: String? = null,
        val scopePackVersion: String? = null,
        val mappingPackVersion: String? = null,
        val mdePackVersion: String? = null,
        val lineTax: List<LineTaxFact> = emptyList(),
    ) : ScopeAnswer

    data object Unavailable : ScopeAnswer
}

/** Scope evaluation; [transactionDate] is the date decided by derivation step 2, never the request date. */
fun interface ScopeEvaluation {
    fun evaluate(
        command: RecordDocumentCommand,
        periodRef: String?,
        transactionDate: LocalDate,
    ): ScopeAnswer
}

/** The version number a record of the document takes. */
fun interface DocumentVersions {
    fun nextVersion(docUuid: UUID): Int
}

/** Writes the result members (the contract's 202 members) as JSON text; the serializer lives in an adapter. */
fun interface ResultJson {
    fun toJson(result: Map<String, Any?>): String
}
