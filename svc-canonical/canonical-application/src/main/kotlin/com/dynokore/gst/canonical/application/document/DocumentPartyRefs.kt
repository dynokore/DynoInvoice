package com.dynokore.gst.canonical.application.document

import java.util.UUID

/** Port of `canonical.document_party_ref`: the counterparty reference of a document (caller's transaction). */
interface DocumentPartyRefs {
    /** Stores [partyRef] for [docUuid] of the bound tenant, replacing a stored value. */
    fun upsert(
        docUuid: UUID,
        partyRef: String,
    )
}
