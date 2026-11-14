package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.document.Direction

/**
 * The counterparty of the entity is the buyer of a SALE and the supplier of a PURCHASE (the entity is the other party
 * of its own document). The lookup is built only when the direction is present, the chosen identifier is not blank and
 * the constitution is known; otherwise it is null and nothing is guessed.
 */
object CounterpartyLookups {
    /** The chosen identifier, or null when the direction is absent or the identifier is blank. */
    fun partyOf(command: RecordDocumentCommand): PartyIdentifier? {
        val party =
            when (command.direction) {
                Direction.SALE -> command.buyer
                Direction.PURCHASE -> command.supplier
                null -> null
            }
        return party?.takeIf { it.scheme.isNotBlank() && it.value.isNotBlank() }
    }

    fun of(
        command: RecordDocumentCommand,
        constitution: String?,
    ): CounterpartyLookup? {
        val party = partyOf(command)
        return if (party == null || constitution == null) {
            null
        } else {
            CounterpartyLookup(command.entityRef, constitution, party.scheme, party.value, command.transactionDate)
        }
    }
}
