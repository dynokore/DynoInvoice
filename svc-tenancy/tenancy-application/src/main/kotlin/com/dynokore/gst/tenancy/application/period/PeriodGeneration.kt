package com.dynokore.gst.tenancy.application.period

import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.platform.calendarandperiods.PeriodKind
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.LocalDate

sealed interface PeriodOutcome {
    /** The records in force after the call, earliest start first. */
    data class Done(
        val periods: List<StoredPeriod>,
    ) : PeriodOutcome

    data object NotFound : PeriodOutcome

    /** The input cannot be met (400 SRT-001); nothing is stored. */
    data class Invalid(
        val reason: String,
    ) : PeriodOutcome

    /** Refused with nothing stored (422 POL-014). */
    data class Refused(
        val reason: String,
    ) : PeriodOutcome
}

/**
 * Period generation (INV-REQ-83, INV-REQ-84, INV-REQ-707): generates the period records of an entity from its
 * registration date, its ACTIVE filing-frequency history and the calendar pack, and stores each with its start, end,
 * return form, due date, kinds, irregular flag and confirmation state. A record already stored is never regenerated
 * here: boundaries change only through the frequency-change flow (INV-REQ-83), so a second run leaves every stored
 * record equal by every field (INV-AC-9542).
 */
class PeriodGeneration(
    private val tx: TransactionRunner,
    private val structure: StructureStore,
    private val planner: PeriodPlanner,
    private val history: FrequencyHistoryStore,
    private val periods: PeriodRecordStore,
) {
    fun generate(entityRef: String): PeriodOutcome =
        tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction PeriodOutcome.NotFound
            generateIn(entity)
        }

    /**
     * The final period (INV-REQ-84 b3, INV-Q-9731 A): the record that contains [registrationEndDate] is replaced by the
     * final period (kind FINAL, the pack's final return form, unconfirmed) and any record starting after it is marked
     * superseded, never deleted. Runs in the caller's transaction when there is one, so `deregisterEntity` can call it
     * in the transaction that records the registration end.
     */
    fun finalPeriod(
        entityRef: String,
        registrationEndDate: LocalDate,
    ): PeriodOutcome =
        tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction PeriodOutcome.NotFound
            val active = history.entries(entity.id).filter { it.state == HistoryState.ACTIVE }
            when (val plan = planner.plan(entity, active, null, registrationEndDate)) {
                is Plan.Invalid -> {
                    PeriodOutcome.Invalid(plan.reason)
                }

                is Plan.Refused -> {
                    PeriodOutcome.Refused(plan.reason)
                }

                is Plan.Periods -> {
                    val final = plan.periods.lastOrNull { PeriodKind.FINAL in it.kinds }
                    if (final == null) {
                        PeriodOutcome.Refused("no final period results for the registration end date")
                    } else {
                        storeFinal(entity, plan.periods.filter { it.start < final.start }, final)
                        PeriodOutcome.Done(periods.inForce(entity.id))
                    }
                }
            }
        }

    /** Generation inside the caller's transaction (also run by the initial `changeFilingFrequency`). */
    internal fun generateIn(entity: EntityRecord): PeriodOutcome {
        val active = history.entries(entity.id).filter { it.state == HistoryState.ACTIVE }
        return when (val plan = planner.plan(entity, active, null, null)) {
            is Plan.Invalid -> {
                PeriodOutcome.Invalid(plan.reason)
            }

            is Plan.Refused -> {
                PeriodOutcome.Refused(plan.reason)
            }

            is Plan.Periods -> {
                insertMissing(entity, plan.periods)
                PeriodOutcome.Done(periods.inForce(entity.id))
            }
        }
    }

    /** Stores the planned periods that start after every record in force; the stored ones are left as they are. */
    private fun insertMissing(
        entity: EntityRecord,
        planned: List<StoredPeriod>,
    ) {
        val lastEnd = periods.inForce(entity.id).maxOfOrNull { it.end }
        planned.filter { lastEnd == null || it.start.isAfter(lastEnd) }.forEach { periods.insert(entity.id, it) }
    }

    private fun storeFinal(
        entity: EntityRecord,
        before: List<StoredPeriod>,
        final: StoredPeriod,
    ) {
        val stored = periods.inForce(entity.id)
        val replaced = stored.filter { !it.start.isBefore(final.start) }
        if (replaced.size == 1 && replaced.single().sameGenerated(final)) return
        val lastEnd = stored.filter { it.start < final.start }.maxOfOrNull { it.end }
        before.filter { lastEnd == null || it.start.isAfter(lastEnd) }.forEach { periods.insert(entity.id, it) }
        periods.supersedeFrom(entity.id, final.start)
        periods.insert(entity.id, final)
    }
}
