package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.derivation.DerivationGate
import com.dynokore.gst.canonical.domain.derivation.DerivationStep
import com.dynokore.gst.canonical.domain.derivation.GateDecision
import com.dynokore.gst.canonical.domain.derivation.HeldDocumentRelease
import com.dynokore.gst.canonical.domain.derivation.HeldDocumentReleaseDecision
import com.dynokore.gst.canonical.domain.derivation.ReleaseRefusal
import com.dynokore.gst.canonical.domain.derivation.StepOutcome
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.util.UUID

/** The outcome of [ReleaseHeldDocument.release]. */
sealed interface ReleaseHeldResult {
    data object Released : ReleaseHeldResult

    data class AlreadyReleased(
        val record: RecordedRelease,
    ) : ReleaseHeldResult

    data class Refused(
        val reason: ReleaseRefusal,
    ) : ReleaseHeldResult

    data object NotHeldWithCode : ReleaseHeldResult
}

/**
 * Releases a document held with a MANUAL code (ING-017, ING-024) by a person (INV-Q-16154 A, INV-REQ-1315), in one
 * transaction. Check order: row lock; permission for the entity of the locked document (before any result about the
 * hold is disclosed, OWASP ASVS 4.1); recorded release (idempotent repeat); held state and code; the
 * [HeldDocumentRelease] rule; writes. An unknown document has no entity to ask about, so it gives NotHeldWithCode.
 * No exception is caught: a failure rolls the transaction back.
 */
class ReleaseHeldDocument(
    private val store: DocumentReleaseStore,
    private val records: HeldReleaseRecords,
    private val authority: ReleaseAuthority,
    private val publisher: ReleasedEventPublisher,
    private val transaction: TransactionRunner,
    private val clock: Clock,
) {
    /** @throws IllegalArgumentException when [userId] is blank. */
    fun release(
        docUuid: UUID,
        holdCode: String,
        userId: String,
    ): ReleaseHeldResult {
        require(userId.isNotBlank()) { "userId must not be blank" }
        return transaction.inTransaction { releaseInTransaction(docUuid, holdCode, userId) }
    }

    private fun releaseInTransaction(
        docUuid: UUID,
        holdCode: String,
        userId: String,
    ): ReleaseHeldResult {
        val doc = store.lockForRelease(docUuid) ?: return ReleaseHeldResult.NotHeldWithCode
        return precheck(doc, holdCode, userId) ?: decideAndApply(doc, holdCode, userId)
    }

    private fun decideAndApply(
        doc: HoldableDocument,
        holdCode: String,
        userId: String,
    ): ReleaseHeldResult {
        val docUuid = doc.docUuid
        return when (
            val decision = HeldDocumentRelease.decide(holdCode, true, userId, records.holdCause(docUuid, holdCode))
        ) {
            is HeldDocumentReleaseDecision.Refused -> {
                ReleaseHeldResult.Refused(decision.reason)
            }

            HeldDocumentReleaseDecision.Released -> {
                applyRelease(docUuid, holdCode, userId, doc)
                ReleaseHeldResult.Released
            }
        }
    }

    /** Permission, recorded release, held state and code, in that order; null when the rule decision is next. */
    private fun precheck(
        doc: HoldableDocument,
        holdCode: String,
        userId: String,
    ): ReleaseHeldResult? {
        if (!authority.permits(userId, doc.entityRef)) return ReleaseHeldResult.Refused(ReleaseRefusal.NOT_PERMITTED)
        val recorded = records.find(doc.docUuid, holdCode)
        return when {
            recorded != null -> ReleaseHeldResult.AlreadyReleased(recorded)
            doc.state != DocumentStates.HELD || holdCode !in doc.holdReasons -> ReleaseHeldResult.NotHeldWithCode
            else -> null
        }
    }

    private fun applyRelease(
        docUuid: UUID,
        holdCode: String,
        userId: String,
        doc: HoldableDocument,
    ) {
        records.record(RecordedRelease(docUuid, holdCode, userId, clock.instant()))
        records.removeReason(docUuid, holdCode)
        if (doc.holdReasons.any { it != holdCode }) return
        val outcomes = DerivationStep.entries.associateWith<DerivationStep, StepOutcome> { StepOutcome.Complete }
        check(DerivationGate.decide(outcomes) == GateDecision.Proceed) { "gate does not proceed for $docUuid" }
        store.markReleased(docUuid)
        publisher.stage(ReleasedEvent(docUuid, listOf(holdCode), doc.entityRef))
    }
}
