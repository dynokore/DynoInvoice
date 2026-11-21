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
    /** The hold reasons of a held fact, or the released hold reasons of a released fact. */
    val reasons: Set<String> = emptySet(),
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
    /** True exactly while the latest fact by sequence is a hold whose reasons contain [HeldDocumentRule.RUL_011]. */
    val rul011: Boolean = false,
)

/** Idempotent, order-insensitive apply by the per-document sequence (last writer by sequence wins). */
object HeldDocumentRule {
    /** The hold reason of a document blocked by RUL-011; the only spelling (exact, case-sensitive match). */
    const val RUL_011 = "RUL-011"

    /** The row to store, or null when nothing changes (stale or repeated event, or a hold without party on no row). */
    fun next(
        current: HeldDocumentRow?,
        fact: HeldDocumentFact,
    ): HeldDocumentRow? {
        val stale = current != null && current.docsequence >= fact.docsequence
        val bareHold = fact.kind == HeldKind.HELD && fact.partyRef == null && current == null
        val names = RUL_011 in fact.reasons
        val kept = !names && current?.rul011 == true
        return when {
            stale || bareHold -> {
                null
            }

            fact.kind == HeldKind.HELD -> {
                HeldDocumentRow(fact.docUuid, fact.docsequence, true, fact.entity, fact.partyRef, names)
            }

            else -> {
                HeldDocumentRow(fact.docUuid, fact.docsequence, false, current?.entity, current?.partyRef, kept)
            }
        }
    }
}
