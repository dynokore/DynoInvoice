package com.dynokore.gst.tenancy.application.period

import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.domain.PeriodPermissions
import com.dynokore.platform.calendarandperiods.PeriodKind
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.LocalDate

/** The kinds of the contract's ConfirmPeriodRequest. */
enum class ConfirmKind { FIRST, FINAL, TRANSITIONAL, FREQUENCY_CHANGE }

data class ConfirmRequest(
    val periodRef: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val kind: ConfirmKind,
)

sealed interface ConfirmOutcome {
    /** The period is confirmed; [repeated] when it already was with these values and nothing was written. */
    data class Done(
        val periodRef: String,
        val start: LocalDate,
        val end: LocalDate,
        val repeated: Boolean = false,
    ) : ConfirmOutcome

    /** 403 SRT-004: the caller does not hold `period.confirm` on the entity; nothing is stored. */
    data class Forbidden(
        val reason: String,
    ) : ConfirmOutcome

    /** 404 SRT-005: the entity, or a period of it, is absent from the caller's scope. */
    data object NotFound : ConfirmOutcome

    /** 422 POL-014: the kind or a date differs from the stored or proposed record; nothing is changed. */
    data class Refused(
        val reason: String,
    ) : ConfirmOutcome
}

/**
 * confirmPeriods (INV-REQ-84 b5, INV-REQ-708 b3; INV-Q-9729 A): a caller holding `period.confirm` (entity.admin,
 * tax.approver) confirms an irregular period record. FIRST and FINAL confirm that one record only. TRANSITIONAL
 * or FREQUENCY_CHANGE naming the `confirmationPeriod` of the entity's PENDING proposal applies the change in one
 * transaction: the entry becomes ACTIVE, the raised items RESOLVED, the records from the change are replaced by
 * the proposal and FilingFrequencyChanged is published once. This is the only way a record becomes confirmed
 * (INV-AC-9543).
 */
class ConfirmPeriods(
    private val tx: TransactionRunner,
    private val structure: StructureStore,
    private val access: PeriodAccess,
    stores: PeriodStores,
    private val publisher: FilingFrequencyChangePublisher,
) {
    private val history = stores.history
    private val proposals = stores.proposals
    private val periods = stores.periods

    fun confirm(
        entityRef: String,
        request: ConfirmRequest,
    ): ConfirmOutcome =
        tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction ConfirmOutcome.NotFound
            access.denial(entityRef, PeriodPermissions.PERIOD_CONFIRM)?.let {
                return@inTransaction ConfirmOutcome.Forbidden(it)
            }
            val pending = history.entries(entity.id).firstOrNull { it.state == HistoryState.PENDING }
            val view = pending?.let { proposalOf(entity.id, it) }
            if (view?.confirmation?.periodRef == request.periodRef) {
                return@inTransaction applyProposal(entityRef, entity.id, pending, view.confirmation, request)
            }
            val stored =
                periods.inForce(entity.id).firstOrNull { it.periodRef == request.periodRef }
                    ?: return@inTransaction ConfirmOutcome.NotFound
            confirmRecord(entity.id, stored, request)
        }

    private fun proposalOf(
        entityId: java.util.UUID,
        entry: FrequencyEntry,
    ): FrequencyView {
        val codes = proposals.items(entityId).filter { it.historyId == entry.id }.map { it.code }
        val computed = proposals.proposedPeriods(entityId, entry.id)
        return FrequencyView(entry, codes, FilingFrequencyCommands.confirmationOf(computed, codes, entry.effectiveFrom))
    }

    private fun confirmRecord(
        entityId: java.util.UUID,
        stored: StoredPeriod,
        request: ConfirmRequest,
    ): ConfirmOutcome {
        val kind = recordKind(request.kind)
        val differs =
            request.periodStart != stored.start ||
                request.periodEnd != stored.end ||
                if (kind != null) kind !in stored.kinds else !isChangeRecord(entityId, stored, request)
        val already = stored.confirmation == ConfirmationState.CONFIRMED
        return when {
            differs -> {
                ConfirmOutcome.Refused("the kind or dates differ from the stored period record")
            }

            already -> {
                ConfirmOutcome.Done(stored.periodRef, stored.start, stored.end, repeated = true)
            }

            else -> {
                periods.confirm(entityId, stored.periodRef)
                ConfirmOutcome.Done(stored.periodRef, stored.start, stored.end)
            }
        }
    }

    /**
     * FREQUENCY_CHANGE or TRANSITIONAL repeated after the change was applied: the record is the one the applied
     * proposal confirmed, which carries its kind in the proposal rows retained with the history entry.
     */
    private fun isChangeRecord(
        entityId: java.util.UUID,
        stored: StoredPeriod,
        request: ConfirmRequest,
    ): Boolean =
        stored.confirmation == ConfirmationState.CONFIRMED &&
            history.entries(entityId).filter { it.state == HistoryState.ACTIVE }.any { entry ->
                val view = proposalOf(entityId, entry)
                view.confirmation?.let { it.periodRef == stored.periodRef && it.kind.name == request.kind.name } == true
            }

    private fun recordKind(kind: ConfirmKind): PeriodKind? =
        when (kind) {
            ConfirmKind.FIRST -> PeriodKind.FIRST
            ConfirmKind.FINAL -> PeriodKind.FINAL
            ConfirmKind.TRANSITIONAL -> null
            ConfirmKind.FREQUENCY_CHANGE -> null
        }

    private fun applyProposal(
        entityRef: String,
        entityId: java.util.UUID,
        pending: FrequencyEntry,
        confirmation: ConfirmationPeriod,
        request: ConfirmRequest,
    ): ConfirmOutcome {
        val previous =
            history
                .entries(entityId)
                .filter { it.state == HistoryState.ACTIVE && it.effectiveFrom.isBefore(pending.effectiveFrom) }
                .maxByOrNull { it.effectiveFrom }
        return when {
            request.kind.name != confirmation.kind.name -> {
                ConfirmOutcome.Refused("the proposal's period is of kind ${confirmation.kind}")
            }

            request.periodStart != confirmation.start || request.periodEnd != confirmation.end -> {
                ConfirmOutcome.Refused("the dates differ from the proposed period")
            }

            previous == null -> {
                ConfirmOutcome.Refused("no filing frequency is in force before ${pending.effectiveFrom}")
            }

            else -> {
                apply(entityRef, entityId, pending, previous, confirmation)
                ConfirmOutcome.Done(confirmation.periodRef, confirmation.start, confirmation.end)
            }
        }
    }

    /** Applies the proposal in the caller's transaction: ACTIVE entry, RESOLVED items, replaced records, one event. */
    private fun apply(
        entityRef: String,
        entityId: java.util.UUID,
        pending: FrequencyEntry,
        previous: FrequencyEntry,
        confirmation: ConfirmationPeriod,
    ) {
        val computed = proposals.proposedPeriods(entityId, pending.id)
        history.setState(entityId, pending.id, HistoryState.ACTIVE)
        proposals.moveItems(entityId, pending.id, ItemState.RESOLVED)
        periods.supersedeFrom(entityId, computed.minOf { it.start })
        computed.forEach {
            periods.insert(
                entityId,
                if (it.periodRef == confirmation.periodRef) it.copy(confirmation = ConfirmationState.CONFIRMED) else it,
            )
        }
        publisher.publish(
            FilingFrequencyChange(
                entityRef,
                previous.frequency,
                pending.frequency,
                computed.first { it.start == pending.effectiveFrom }.periodRef,
            ),
        )
    }
}
