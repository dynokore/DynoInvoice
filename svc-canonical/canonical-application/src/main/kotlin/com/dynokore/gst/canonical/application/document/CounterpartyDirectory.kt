package com.dynokore.gst.canonical.application.document

import java.time.LocalDate

/** The document's party identifier pair and the context masterdata needs to resolve the counterparty. */
data class CounterpartyLookup(
    val entityRef: String,
    val constitution: String,
    val partyScheme: String,
    val partyValue: String,
    val transactionDate: LocalDate,
)

/** What the owner of party identity (masterdata-svc) said about a lookup. */
sealed interface PartyRefAnswer {
    data class Found(
        val partyRef: String,
    ) : PartyRefAnswer

    /** The owner answered and holds no counterparty for the pair. */
    object NotFound : PartyRefAnswer

    /** No usable answer (owner down, refusal other than not-found, malformed reference). */
    object Unavailable : PartyRefAnswer
}

/** Port of the counterparty owner (ARC-03). A call may take up to the edge deadline: never inside a transaction. */
interface CounterpartyDirectory {
    fun partyRefOf(lookup: CounterpartyLookup): PartyRefAnswer
}
