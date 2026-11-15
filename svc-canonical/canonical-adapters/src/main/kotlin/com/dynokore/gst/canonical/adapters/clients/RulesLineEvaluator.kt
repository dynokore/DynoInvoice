package com.dynokore.gst.canonical.adapters.clients

import com.dynokore.gst.canonical.application.document.LineAnswer
import com.dynokore.gst.canonical.application.document.LineEvaluator
import com.dynokore.gst.canonical.application.document.LineScopeFacts

/**
 * [LineEvaluator] over `evaluateDocument` of rules-svc (one call per line). Not a Spring component of its own: the
 * configuration wires it. A verdict is relayed; no answer and a refusal both fail closed to Unavailable (the
 * document holds, INV-REQ-1208). `counterpartySchemeData` is sent only when scheme facts exist.
 */
class RulesLineEvaluator(
    private val evaluate: (EvaluationFacts) -> RulesAnswer,
) : LineEvaluator {
    constructor(client: RulesClient) : this(client::evaluate)

    override fun evaluate(facts: LineScopeFacts): LineAnswer =
        when (val answer = evaluate.invoke(request(facts))) {
            is RulesAnswer.Verdict -> LineAnswer.Verdict(answer.scopeVerdict)
            is RulesAnswer.Refused -> LineAnswer.Unavailable
            RulesAnswer.Unavailable -> LineAnswer.Unavailable
        }

    private fun request(facts: LineScopeFacts) =
        EvaluationFacts(
            entityRef = facts.entityRef,
            transactionDate = facts.transactionDate,
            internalTaxCode = facts.internalTaxCode,
            electedSchemes = facts.electedSchemes,
            counterpartySchemeData =
                facts.counterpartyScheme?.let { SchemeData(it.approvedSchemes, it.arcsGstChargeAgreed) },
        )
}
