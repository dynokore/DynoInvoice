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
}
