package com.dynokore.gst.canonical.application.document

import java.time.LocalDate

/**
 * Scope evaluation: one evaluateDocument call per line, then the combination function. [transactionDate] is the
 * derived date (election-aware), used for the elections read and for every call; never the request date.
 */
class ScopeEvaluationService(
    private val constitutions: EntityConstitutions,
    private val schemes: SchemeElections,
    private val counterparty: CounterpartySchemeSource,
    private val evaluator: LineEvaluator,
    private val combination: (List<String>) -> String = LineVerdictCombination::combine,
) : ScopeEvaluation {
    override fun evaluate(
        command: RecordDocumentCommand,
        periodRef: String?,
        transactionDate: LocalDate,
    ): ScopeAnswer {
        val facts = counterpartyFacts(command)
        return if (command.lines.isEmpty() || facts == SchemeFactsAnswer.Unavailable) {
            ScopeAnswer.Unavailable
        } else {
            val scheme = (facts as SchemeFactsAnswer.Found).facts
            val elected = schemes.electedSchemesOf(command.tenantId, command.entityRef, transactionDate)
            val verdicts = mutableListOf<LineAnswer.Verdict>()
            for (line in command.lines) {
                val scoped = LineScopeFacts(command.entityRef, transactionDate, line.internalTaxCode, elected, scheme)
                val answer = evaluator.evaluate(scoped)
                if (answer !is LineAnswer.Verdict) return ScopeAnswer.Unavailable
                verdicts += answer
            }
            answerOf(verdicts)
        }
    }

    /**
     * A line without a category code or rate, or lines that disagree on the pack versions, the applied packs or the
     * document type code (null equals null only), give Unavailable: the document holds (fail closed, INV-REQ-1208).
     */
    private fun answerOf(verdicts: List<LineAnswer.Verdict>): ScopeAnswer {
        val first = verdicts.first()
        val lineTax = verdicts.map { LineTaxFact(it.categoryCode.orEmpty(), it.rate.orEmpty()) }
        val incomplete = lineTax.any { it.categoryCode.isBlank() || it.rate.isBlank() }
        val disagree =
            verdicts.any {
                it.scopePackVersion != first.scopePackVersion ||
                    it.mappingPackVersion != first.mappingPackVersion ||
                    it.mdePackVersion != first.mdePackVersion ||
                    it.appliedPacks != first.appliedPacks ||
                    it.documentTypeCode != first.documentTypeCode
            }
        return if (incomplete || disagree) {
            ScopeAnswer.Unavailable
        } else {
            ScopeAnswer.Verdict(
                scopeVerdict = combination(verdicts.map { it.scopeVerdict }),
                documentTypeCode = first.documentTypeCode,
                scopePackVersion = first.scopePackVersion,
                mappingPackVersion = first.mappingPackVersion,
                mdePackVersion = first.mdePackVersion,
                lineTax = lineTax,
                appliedPacks = first.appliedPacks,
            )
        }
    }

    /** A document without a counterparty lookup has no counterparty facts; the source is asked at most once. */
    private fun counterpartyFacts(command: RecordDocumentCommand): SchemeFactsAnswer {
        val lookup =
            CounterpartyLookups.partyOf(command)?.let {
                CounterpartyLookups.of(command, constitutions.constitutionOf(command.tenantId, command.entityRef))
            }
        return if (command.lines.isEmpty() || lookup == null) {
            SchemeFactsAnswer.Found(null)
        } else {
            counterparty.schemeFactsOf(lookup)
        }
    }
}
