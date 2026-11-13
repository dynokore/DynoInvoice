package com.dynokore.gst.canonical.adapters.clients

import com.dynokore.gst.canonical.application.document.CounterpartyDirectory
import com.dynokore.gst.canonical.application.document.CounterpartyLookup
import com.dynokore.gst.canonical.application.document.PARTY_REF_MAX_LENGTH
import com.dynokore.gst.canonical.application.document.PartyRefAnswer
import java.time.LocalDate

/**
 * [CounterpartyDirectory] over `resolveCounterparty` of masterdata-svc. Not a Spring component: the caller task wires it.
 * A reference of 1 to 40 characters is Found; no counterparty (answered null, or 404) is NotFound; any other refusal, no
 * answer, and a blank or over-long reference are Unavailable (a malformed reference is not a fact).
 */
class MasterDataCounterpartyDirectory(
    private val resolve: (String, String, String, String, LocalDate) -> MasterDataAnswer<ResolvedCounterparty>,
) : CounterpartyDirectory {
    constructor(client: MasterDataClient) : this(client::resolveCounterparty)

    override fun partyRefOf(lookup: CounterpartyLookup): PartyRefAnswer =
        when (
            val answer =
                resolve(lookup.entityRef, lookup.constitution, lookup.partyScheme, lookup.partyValue, lookup.transactionDate)
        ) {
            is MasterDataAnswer.Answered -> fromRef(answer.value.partyRef)
            is MasterDataAnswer.Refused ->
                if (answer.status == NOT_FOUND) PartyRefAnswer.NotFound else PartyRefAnswer.Unavailable
            is MasterDataAnswer.Unavailable -> PartyRefAnswer.Unavailable
        }

    private fun fromRef(ref: String?): PartyRefAnswer =
        when {
            ref == null -> PartyRefAnswer.NotFound
            ref.isBlank() || ref.length > PARTY_REF_MAX_LENGTH -> PartyRefAnswer.Unavailable
            else -> PartyRefAnswer.Found(ref)
        }

    private companion object {
        const val NOT_FOUND = 404
    }
}
