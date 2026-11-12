package com.dynokore.gst.canonical.application.document

import java.util.UUID

/** The longest `partyRef` of the DocumentHeld payload schema. */
const val PARTY_REF_MAX_LENGTH = 40

/**
 * Stores the counterparty reference the record step received from masterdata, once per document. Runs in the caller's
 * transaction, opens none, and is idempotent; a later record replaces the value (an amendment).
 */
class RecordPartyRef(
    private val store: DocumentPartyRefs,
) {
    fun record(
        docUuid: UUID,
        partyRef: String,
    ) {
        require(partyRef.isNotBlank()) { "partyRef must not be blank" }
        require(partyRef.length <= PARTY_REF_MAX_LENGTH) { "partyRef is longer than $PARTY_REF_MAX_LENGTH characters" }
        store.upsert(docUuid, partyRef)
    }
}
