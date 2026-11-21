package com.dynokore.gst.masterdata.application.held

import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import java.util.UUID

/** The held-documents replica of the bound tenant (TPL-11); every call runs inside the caller's transaction. */
interface HeldDocuments {
    /** Applies [fact] by [HeldDocumentRule]: one upsert guarded by the sequence; a null result writes nothing. */
    fun apply(fact: HeldDocumentFact)

    /** The docUuids of the held documents of [entity] and [party], ascending by the UUID text. */
    fun heldFor(
        entity: String,
        party: PartyRef,
    ): List<UUID>

    /**
     * The party references of the held rows of [entity] with the RUL-011 mark. A row without a party reference is
     * never listed (the contract makes partyRef optional); the result carries no duplicates.
     */
    fun rul011Blocked(entity: String): Set<PartyRef>
}
