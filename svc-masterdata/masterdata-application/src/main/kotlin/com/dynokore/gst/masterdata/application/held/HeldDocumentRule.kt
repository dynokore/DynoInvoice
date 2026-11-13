package com.dynokore.gst.masterdata.application.held

import java.util.UUID

enum class HeldKind { HELD, RELEASED }

/** One DocumentHeld or DocumentReleased event; [docsequence] is the per-document monotonic sequence. */
data class HeldDocumentFact(
    val kind: HeldKind,
    val docUuid: UUID,
    val docsequence: Long,
    val entity: String?,
    val partyRef: String?,
) {
    init {
        require(docsequence >= 1) { "a docsequence is 1 or more" }
        require(entity == null || entity.isNotBlank()) { "an entity is not blank" }
        require(partyRef == null || partyRef.isNotBlank()) { "a party reference is not blank" }
        require(kind != HeldKind.HELD || entity != null) { "a held fact carries its entity" }
    }
}

/** The replica row of one document of the bound tenant; a released row is a tombstone that keeps its sequence. */
data class HeldDocumentRow(
    val docUuid: UUID,
    val docsequence: Long,
    val held: Boolean,
    val entity: String?,
    val partyRef: String?,
)

/** Idempotent, order-insensitive apply by the per-document sequence (last writer by sequence wins). */
object HeldDocumentRule {
    /** The row to store, or null when nothing changes (stale or repeated event, or a hold without party on no row). */
    fun next(
        current: HeldDocumentRow?,
        fact: HeldDocumentFact,
    ): HeldDocumentRow? {
        val stale = current != null && current.docsequence >= fact.docsequence
        val bareHold = fact.kind == HeldKind.HELD && fact.partyRef == null && current == null
        val held = fact.kind == HeldKind.HELD
        return when {
            stale || bareHold -> null
            held -> HeldDocumentRow(fact.docUuid, fact.docsequence, true, fact.entity, fact.partyRef)
            else -> HeldDocumentRow(fact.docUuid, fact.docsequence, false, current?.entity, current?.partyRef)
        }
    }
}
