package com.dynokore.gst.masterdata.application.identity

import com.dynokore.gst.masterdata.domain.identity.ChangeState
import com.dynokore.gst.masterdata.domain.identity.ConfirmationRefusal
import com.dynokore.gst.masterdata.domain.identity.ConfirmationRule
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.PendingChange
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.gst.masterdata.domain.identity.TripleVersion
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/** `confirmIdentityTripleChange`: [confirmer] is the authenticated subject of the call, never a body field. */
data class ConfirmCommand(
    val entity: EntityRef,
    val changeRef: String,
    val confirmer: PersonRef,
)

sealed interface ConfirmOutcome {
    /** The change is confirmed; [version] applies from its effective date. */
    data class Confirmed(
        val version: TripleVersion,
    ) : ConfirmOutcome

    /** The entity has no such change (SRT-005). */
    data object UnknownChange : ConfirmOutcome

    /** The confirmation does not count; the change stays pending (SRT-004). */
    data class Refused(
        val reason: ConfirmationRefusal,
    ) : ConfirmOutcome

    /** The change is no longer PENDING_CONFIRMATION (SRT-008). */
    data object NotPending : ConfirmOutcome
}

/**
 * INV-REQ-1007, INV-REQ-1270: the change takes effect only when a different person from the proposer, holding a role of
 * the confirmer set, confirms it. The order of the checks: unknown change, then who confirms, then the state, so a
 * caller who may not confirm learns nothing of the state. A refused confirmation stores nothing.
 *
 * INV-REQ-1006, INV-Q-7876 A: a change whose effective date is earlier than the date of the confirmation (the clock's
 * date in UTC), and which replaces a version already in force on that date, is a correction of the past; it appends
 * `IdentityTripleCorrected` in the same transaction as the write of the triple and the move to CONFIRMED. Every
 * confirmed change also appends exactly one `IdentityTripleChanged` (INV-REQ-1135, INV-Q-16401 A) in that transaction.
 */
class ConfirmIdentityTripleChange(
    private val tx: TransactionRunner,
    private val changes: PendingChangeRepository,
    private val triples: TripleRepository,
    private val authority: ConfirmerAuthority,
    private val corrections: BackdatedCorrections,
) {
    fun handle(command: ConfirmCommand): ConfirmOutcome =
        tx.inTransaction {
            val change = changes.find(command.entity, command.changeRef)
            if (change == null) {
                ConfirmOutcome.UnknownChange
            } else {
                val refusal =
                    ConfirmationRule.check(
                        change.proposedBy,
                        command.confirmer,
                        authority.permits(command.confirmer, command.entity),
                    )
                if (refusal != null) {
                    ConfirmOutcome.Refused(refusal)
                } else if (change.state != ChangeState.PENDING_CONFIRMATION ||
                    !changes.markConfirmed(change, command.confirmer)
                ) {
                    ConfirmOutcome.NotPending
                } else {
                    val correction = corrections.isCorrection(change)
                    triples.save(change.version, change.proposedBy, command.confirmer)
                    corrections.publishChanged(change)
                    if (correction) corrections.publish(change)
                    ConfirmOutcome.Confirmed(change.version)
                }
            }
        }
}

/**
 * INV-REQ-1006, INV-Q-7876 A: a change whose effective date is earlier than the date of the confirmation (the clock's
 * date in UTC), and which replaces a version already in force on that date, is a correction of the past.
 */
class BackdatedCorrections(
    private val triples: TripleRepository,
    private val clock: Clock,
    private val publisher: IdentityTripleCorrectedPublisher,
    private val changedPublisher: IdentityTripleChangedPublisher,
) {
    /** Read before the save: a stored version on or before today must already exist for the same constitution. */
    fun isCorrection(change: PendingChange): Boolean {
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        return change.version.effectiveFrom.isBefore(today) &&
            triples
                .versions(change.version.entity, change.version.constitution)
                .any { !it.effectiveFrom.isAfter(today) }
    }

    fun publish(change: PendingChange) = publisher.publish(change)

    /** INV-REQ-1135, INV-Q-16401 A: every confirmed change appends `IdentityTripleChanged` in the same transaction. */
    fun publishChanged(change: PendingChange) = changedPublisher.publish(change)
}

/**
 * Who may confirm: the tenant's confirmer set (data only while VP-09 is `planned`, never read for the decision) and the
 * permission `entity-config.confirm` at an entity, decided per request (INV-Q-10027).
 */
class ConfirmerAuthority(
    private val policy: ConfirmerPolicy,
    private val permission: ConfirmerPermission,
) {
    fun confirmers() = policy.confirmers()

    fun decide(
        subject: PersonRef,
        entity: EntityRef,
    ) = permission.decide(subject, entity)

    /** True only when the decision allows with source LOCAL or FALLBACK (INV-Q-10025); fail closed. */
    fun permits(
        subject: PersonRef,
        entity: EntityRef,
    ): Boolean =
        try {
            val decision = decide(subject, entity)
            decision.allowed && decision.source != DecisionSource.DEGRADED
        } catch (_: Exception) {
            false
        }
}
