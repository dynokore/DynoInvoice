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

/** The last sequence and state of one hold reason of one document: held, or released. */
data class ReasonMark(
    val docsequence: Long,
    val held: Boolean,
)

/**
 * The replica row of one document of the bound tenant. [marks] keeps the last-writer-wins state of each reason by
 * sequence; [held] is true exactly while some mark is held; [docsequence] is the highest sequence seen; [entity] and
 * [partyRef] come from the hold with the highest sequence, which is [identityDocsequence] (null when no hold was seen).
 */
data class HeldDocumentRow(
    val docUuid: UUID,
    val docsequence: Long,
    val held: Boolean,
    val entity: String?,
    val partyRef: String?,
    val identityDocsequence: Long?,
    val marks: Map<String, ReasonMark>,
)

/**
 * Idempotent, commutative apply (a convergent replica): a fact replaces the mark of each reason it names only when its
 * sequence is higher, a hold replaces entity and party only when its sequence is higher than the identity sequence.
 * Any order of the same facts, or a repeat, ends in the same row.
 */
object HeldDocumentRule {
    /** The reason a fact without reasons stands for (the handlers never produce one: the contract needs a reason). */
    const val NO_REASON = ""

    /** The row to store, or null when nothing changes (a stale or repeated fact). */
    fun next(
        current: HeldDocumentRow?,
        fact: HeldDocumentFact,
    ): HeldDocumentRow? {
        val isHold = fact.kind == HeldKind.HELD
        val marks = current?.marks.orEmpty().toMutableMap()
        for (reason in fact.reasons.ifEmpty { setOf(NO_REASON) }) {
            val known = marks[reason]?.docsequence ?: 0L
            if (known < fact.docsequence) marks[reason] = ReasonMark(fact.docsequence, isHold)
        }
        val newIdentity = isHold && (current?.identityDocsequence ?: 0L) < fact.docsequence
        val result =
            HeldDocumentRow(
                fact.docUuid,
                maxOf(current?.docsequence ?: 0L, fact.docsequence),
                marks.values.any { it.held },
                if (newIdentity) fact.entity else current?.entity,
                if (newIdentity) fact.partyRef else current?.partyRef,
                if (newIdentity) fact.docsequence else current?.identityDocsequence,
                marks,
            )
        return result.takeIf { it != current }
    }
}
