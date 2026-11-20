package com.dynokore.gst.canonical.application.document

import java.time.LocalDate
import java.util.UUID

/** One document line as far as scope evaluation is concerned. */
data class RecordLine(
    val internalTaxCode: String,
)

/** The GST schemes an entity has elected, in force on a date (fed from EntityProfileChanged). */
fun interface SchemeElections {
    fun electedSchemesOf(
        tenantId: UUID,
        entityRef: String,
        on: LocalDate,
    ): List<String>
}

/** The scheme facts of a counterparty, as resolved by the existing client. */
data class SchemeFacts(
    val approvedSchemes: List<String>,
    val arcsGstChargeAgreed: Boolean?,
)

sealed interface SchemeFactsAnswer {
    /** The source answered; [facts] is null when it holds none for the counterparty. */
    data class Found(
        val facts: SchemeFacts?,
    ) : SchemeFactsAnswer

    data object Unavailable : SchemeFactsAnswer
}

/** Port: the counterparty scheme facts (answer of resolveCounterparty). */
fun interface CounterpartySchemeSource {
    fun schemeFactsOf(lookup: CounterpartyLookup): SchemeFactsAnswer
}

/** The inputs of one evaluateDocument call (one document line). */
data class LineScopeFacts(
    val entityRef: String,
    val transactionDate: LocalDate,
    val internalTaxCode: String,
    val electedSchemes: List<String>,
    val counterpartyScheme: SchemeFacts?,
)

/** One applied pack as the evaluation result names it: type, registry identifier and version, copied unchanged. */
data class AppliedPackFact(
    val packType: String,
    val packIdentifier: String,
    val version: String,
)

sealed interface LineAnswer {
    data class Verdict(
        val scopeVerdict: String,
        val categoryCode: String? = null,
        val rate: String? = null,
        val scopePackVersion: String? = null,
        val mappingPackVersion: String? = null,
        val mdePackVersion: String? = null,
        val documentTypeCode: String? = null,
        val appliedPacks: List<AppliedPackFact> = emptyList(),
        val supplyType: String? = null,
    ) : LineAnswer

    data object Unavailable : LineAnswer
}

/** Port: one evaluateDocument call at rules-svc for one line. */
fun interface LineEvaluator {
    fun evaluate(facts: LineScopeFacts): LineAnswer
}
