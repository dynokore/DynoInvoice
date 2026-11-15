package com.dynokore.gst.canonical.adapters.clients

import com.dynokore.gst.canonical.application.document.CounterpartyLookup
import com.dynokore.gst.canonical.application.document.CounterpartySchemeSource
import com.dynokore.gst.canonical.application.document.SchemeFacts
import com.dynokore.gst.canonical.application.document.SchemeFactsAnswer
import java.time.LocalDate

/**
 * [CounterpartySchemeSource] over `resolveCounterparty` of masterdata-svc (schemes outside their window on the lookup
 * date are dropped; a second bounded call per record; the
 * contract says the resolution is cached and fast). An answered counterparty is Found with its scheme facts (absent
 * schemes read as empty); no counterparty (answered without one, or 404) is Found(null); any other refusal and no
 * answer are Unavailable (fail closed).
 */
class MasterDataCounterpartySchemes(
    private val resolve: (String, String, String, String, LocalDate) -> MasterDataAnswer<ResolvedCounterparty>,
) : CounterpartySchemeSource {
    constructor(client: MasterDataClient) : this(client::resolveCounterparty)

    override fun schemeFactsOf(lookup: CounterpartyLookup): SchemeFactsAnswer =
        when (
            val answer =
                resolve(
                    lookup.entityRef,
                    lookup.constitution,
                    lookup.partyScheme,
                    lookup.partyValue,
                    lookup.transactionDate,
                )
        ) {
            is MasterDataAnswer.Answered -> {
                val counterparty = answer.value
                SchemeFactsAnswer.Found(
                    if (counterparty.partyRef == null) {
                        null
                    } else {
                        SchemeFacts(
                            inForce(counterparty.approvedSchemes, lookup.transactionDate),
                            counterparty.arcsGstChargeAgreed,
                        )
                    },
                )
            }

            is MasterDataAnswer.Refused -> {
                if (answer.status == NOT_FOUND) SchemeFactsAnswer.Found(null) else SchemeFactsAnswer.Unavailable
            }

            is MasterDataAnswer.Unavailable -> {
                SchemeFactsAnswer.Unavailable
            }
        }

    /** A scheme counts as approved only inside its window on the date of the lookup (both bounds inclusive). */
    private fun inForce(
        windows: List<ApprovedSchemeWindow>?,
        on: LocalDate,
    ): List<String> =
        windows
            .orEmpty()
            .filter { it.effectiveFrom <= on && (it.effectiveTo == null || on <= it.effectiveTo) }
            .map { it.scheme }

    private companion object {
        const val NOT_FOUND = 404
    }
}
