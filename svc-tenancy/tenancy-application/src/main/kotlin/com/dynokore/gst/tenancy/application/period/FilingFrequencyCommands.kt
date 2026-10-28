package com.dynokore.gst.tenancy.application.period

import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.domain.PeriodPermissions
import com.dynokore.platform.calendarandperiods.PeriodKind
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.LocalDate
import java.util.UUID

/** A request to record a filing frequency from an effective date (contract FilingFrequencyChangeRequest). */
data class FrequencyRequest(
    val newFrequency: String,
    val effectiveFrom: LocalDate,
    val irasApprovalReference: String,
)

/** What a proposal asks `confirmPeriods` to confirm: the period of the contract's `confirmationPeriod`. */
enum class ConfirmationKind { TRANSITIONAL, FREQUENCY_CHANGE }

data class ConfirmationPeriod(
    val periodRef: String,
    val start: LocalDate,
    val end: LocalDate,
    val kind: ConfirmationKind,
)

/** The recorded entry as the response presents it (INV-Q-9727 A). */
data class FrequencyView(
    val entry: FrequencyEntry,
    val raised: List<RaisedCode>,
    val confirmation: ConfirmationPeriod?,
) {
    /** A recorded proposal presents PENDING_CONFIRMATION, before and after its apply (INV-Q-9727); else ACTIVE. */
    val proposed: Boolean get() = raised.isNotEmpty()
}

sealed interface FrequencyOutcome {
    data class Done(
        val view: FrequencyView,
    ) : FrequencyOutcome

    data object NotFound : FrequencyOutcome

    /** 403 SRT-004: the caller does not hold `filing-frequency.change` on the entity; nothing is stored. */
    data class Forbidden(
        val reason: String,
    ) : FrequencyOutcome

    /** 400 SRT-001: nothing is stored. */
    data class Invalid(
        val reason: String,
    ) : FrequencyOutcome

    /** 422 POL-014: nothing is stored. */
    data class Refused(
        val reason: String,
    ) : FrequencyOutcome
}

/**
 * changeFilingFrequency (INV-REQ-705, INV-REQ-688, INV-REQ-708; INV-Q-9723 A, INV-Q-9727 A). On an entity without
 * history it records the initial frequency in state ACTIVE and runs the period generation, raising and publishing
 * nothing. Otherwise it stores, in one transaction, a PENDING history entry, the proposal (the periods the generator
 * computes over the ACTIVE entries plus the PENDING one, held apart from the period records) and one OPEN raised item
 * per code: PER-004 always, PER-005 when a TRANSITIONAL period results. It changes no period record and publishes no
 * event; `confirmPeriods` applies the change.
 */
class FilingFrequencyCommands(
    private val tx: TransactionRunner,
    private val structure: StructureStore,
    private val planner: PeriodPlanner,
    private val generation: PeriodGeneration,
    private val access: PeriodAccess,
    stores: PeriodStores,
) {
    private val history = stores.history
    private val proposals = stores.proposals
    private val periods = stores.periods

    fun changeFilingFrequency(
        entityRef: String,
        request: FrequencyRequest,
    ): FrequencyOutcome =
        tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction FrequencyOutcome.NotFound
            access.denial(entityRef, PeriodPermissions.FILING_FREQUENCY_CHANGE)?.let {
                return@inTransaction FrequencyOutcome.Forbidden(it)
            }
            val entries = history.entries(entity.id).filter { it.state != HistoryState.SUPERSEDED }
            val same = entries.firstOrNull { it.effectiveFrom == request.effectiveFrom }
            val pending = entries.firstOrNull { it.state == HistoryState.PENDING }
            when {
                same != null && sameValues(same, request) -> {
                    FrequencyOutcome.Done(view(entity, same))
                }

                same != null && same.state == HistoryState.ACTIVE -> {
                    FrequencyOutcome.Refused("a filing frequency is already recorded from this date with other values")
                }

                pending != null && pending.effectiveFrom != request.effectiveFrom -> {
                    FrequencyOutcome.Refused(
                        "a change effective ${pending.effectiveFrom} is pending confirmation; confirm it first",
                    )
                }

                entries.none { it.state == HistoryState.ACTIVE } -> {
                    initial(entity, request)
                }

                else -> {
                    propose(entity, entries.filter { it.state == HistoryState.ACTIVE }, pending, request)
                }
            }
        }

    private fun sameValues(
        entry: FrequencyEntry,
        request: FrequencyRequest,
    ) = entry.frequency == request.newFrequency && entry.irasApprovalReference == request.irasApprovalReference

    private fun entry(
        request: FrequencyRequest,
        state: HistoryState,
    ) = FrequencyEntry(
        UUID.randomUUID(),
        request.effectiveFrom,
        request.newFrequency,
        request.irasApprovalReference,
        state,
    )

    private fun initial(
        entity: EntityRecord,
        request: FrequencyRequest,
    ): FrequencyOutcome {
        val recorded = entry(request, HistoryState.ACTIVE)
        return when (val plan = planner.plan(entity, listOf(recorded), null, null)) {
            is Plan.Invalid -> {
                FrequencyOutcome.Invalid(plan.reason)
            }

            is Plan.Refused -> {
                FrequencyOutcome.Refused(plan.reason)
            }

            is Plan.Periods -> {
                history.insert(entity.id, recorded)
                when (val outcome = generation.generateIn(entity)) {
                    is PeriodOutcome.Done -> FrequencyOutcome.Done(FrequencyView(recorded, emptyList(), null))
                    is PeriodOutcome.Invalid -> FrequencyOutcome.Invalid(outcome.reason)
                    is PeriodOutcome.Refused -> FrequencyOutcome.Refused(outcome.reason)
                    PeriodOutcome.NotFound -> FrequencyOutcome.NotFound
                }
            }
        }
    }

    private fun propose(
        entity: EntityRecord,
        active: List<FrequencyEntry>,
        pending: FrequencyEntry?,
        request: FrequencyRequest,
    ): FrequencyOutcome {
        val proposed = entry(request, HistoryState.PENDING)
        return when (val plan = planner.plan(entity, active + proposed, request.effectiveFrom, null)) {
            is Plan.Invalid -> FrequencyOutcome.Invalid(plan.reason)
            is Plan.Refused -> FrequencyOutcome.Refused(plan.reason)
            is Plan.Periods -> storeProposal(entity, pending, proposed, plan.periods)
        }
    }

    private fun storeProposal(
        entity: EntityRecord,
        pending: FrequencyEntry?,
        proposed: FrequencyEntry,
        computed: List<StoredPeriod>,
    ): FrequencyOutcome {
        val effectiveFrom = proposed.effectiveFrom
        val boundary = periods.inForce(entity.id).firstOrNull { effectiveFrom in it }?.start ?: effectiveFrom
        val affected = computed.filter { !it.start.isBefore(boundary) }
        val codes =
            listOf(RaisedCode.PER_004) +
                if (affected.any { PeriodKind.TRANSITIONAL in it.kinds }) listOf(RaisedCode.PER_005) else emptyList()
        return if (confirmationOf(affected, codes, effectiveFrom) == null ||
            affected.none { it.start == effectiveFrom }
        ) {
            FrequencyOutcome.Refused("no period of the new frequency starts on $effectiveFrom")
        } else {
            pending?.let {
                history.setState(entity.id, it.id, HistoryState.SUPERSEDED)
                proposals.moveItems(entity.id, it.id, ItemState.SUPERSEDED)
            }
            history.insert(entity.id, proposed)
            proposals.insertProposed(entity.id, proposed.id, affected)
            codes.forEach { proposals.raise(entity.id, proposed.id, it) }
            FrequencyOutcome.Done(view(entity, proposed))
        }
    }

    /** The response for [entry]: one stored with a proposal presents it, whatever its state now; else its state only. */
    private fun view(
        entity: EntityRecord,
        entry: FrequencyEntry,
    ): FrequencyView {
        val codes = proposals.items(entity.id).filter { it.historyId == entry.id }.map { it.code }
        val computed = proposals.proposedPeriods(entity.id, entry.id)
        return FrequencyView(entry, codes, confirmationOf(computed, codes, entry.effectiveFrom))
    }

    companion object {
        /**
         * The period `confirmPeriods` confirms to apply a proposal: the TRANSITIONAL one when PER-005 was raised,
         * otherwise the first period of the new frequency (the one starting on [effectiveFrom]), kind FREQUENCY_CHANGE.
         */
        fun confirmationOf(
            computed: List<StoredPeriod>,
            codes: List<RaisedCode>,
            effectiveFrom: LocalDate,
        ): ConfirmationPeriod? {
            val (period, kind) =
                if (RaisedCode.PER_005 in codes) {
                    computed.firstOrNull { PeriodKind.TRANSITIONAL in it.kinds } to ConfirmationKind.TRANSITIONAL
                } else {
                    computed.firstOrNull { it.start == effectiveFrom } to ConfirmationKind.FREQUENCY_CHANGE
                }
            return period?.let { ConfirmationPeriod(it.periodRef, it.start, it.end, kind) }
        }
    }
}
