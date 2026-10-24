package com.dynokore.gst.tenancy.application.period

import com.dynokore.gst.tenancy.application.status.EntityStatusStore
import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.domain.StatusKind
import com.dynokore.platform.calendarandperiods.CalendarPack
import com.dynokore.platform.calendarandperiods.CalendarPackCoverageException
import com.dynokore.platform.calendarandperiods.FilingFrequency
import com.dynokore.platform.calendarandperiods.FrequencyChange
import com.dynokore.platform.calendarandperiods.FrequencyHistory
import com.dynokore.platform.calendarandperiods.PackPeriodGenerator
import com.dynokore.platform.calendarandperiods.PeriodRequest
import java.time.LocalDate

/** What the planner could compute, or why it refuses. */
sealed interface Plan {
    data class Periods(
        val periods: List<StoredPeriod>,
    ) : Plan

    /** The request cannot be met by this input (400 SRT-001): nothing is stored. */
    data class Invalid(
        val reason: String,
    ) : Plan

    /** The owning service refuses or cannot check it (422 POL-014): nothing is stored. */
    data class Refused(
        val reason: String,
    ) : Plan
}

/**
 * Builds the `PeriodRequest` of the calendar-and-periods library for one entity and runs `PackPeriodGenerator`
 * (INV-Q-9723 A, INV-Q-9725 A): the registration date is the `registration_start_date` of the OBLIGATION_START status
 * row with the greatest `effective_from` that has one; the pack comes from the port; `through` is the start of the last
 * window of the frequency in force at the end of the history; the VP-62 rule is read for the entity. Every input that
 * is missing refuses and stores nothing (fail closed).
 */
class PeriodPlanner(
    private val statuses: EntityStatusStore,
    private val packs: CalendarPackSource,
    private val rules: NonBusinessDayRuleReader,
) {
    /** The registration start date recorded for the entity, or null. */
    fun registrationDate(entity: EntityRecord): LocalDate? =
        statuses
            .history(entity.id)
            .filter { it.kind == StatusKind.OBLIGATION_START && it.registrationStartDate != null }
            .maxByOrNull { it.effectiveFrom }
            ?.registrationStartDate

    /** The periods for [entries] (non-superseded, by effective date); [packOn] selects the pack in force. */
    fun plan(
        entity: EntityRecord,
        entries: List<FrequencyEntry>,
        packOn: LocalDate?,
        deregistrationDate: LocalDate?,
    ): Plan {
        val registration = registrationDate(entity)
        val pack = registration?.let { packs.inForce(packOn ?: it) }
        val sorted = entries.sortedBy { it.effectiveFrom }
        val refusal = refusalOf(registration, pack, sorted, deregistrationDate)
        return refusal
            ?: generate(
                entity,
                sorted,
                Inputs(checkNotNull(registration), checkNotNull(pack), packOn ?: registration, deregistrationDate),
            )
    }

    /** The inputs every refusal check has passed; [ruleOn] is the date VP-62 is resolved on. */
    private class Inputs(
        val registration: LocalDate,
        val pack: CalendarPack,
        val ruleOn: LocalDate,
        val deregistrationDate: LocalDate?,
    )

    /** Why nothing can be planned, or null when every input is there (fail closed). */
    private fun refusalOf(
        registration: LocalDate?,
        pack: CalendarPack?,
        sorted: List<FrequencyEntry>,
        deregistrationDate: LocalDate?,
    ): Plan? =
        when {
            registration == null -> {
                Plan.Refused("registration start date not recorded")
            }

            sorted.isEmpty() -> {
                Plan.Refused("no filing frequency is recorded for the entity")
            }

            pack == null -> {
                Plan.Refused("calendar pack unknown")
            }

            pack.windowsOf(FilingFrequency(sorted.last().frequency)).isEmpty() -> {
                Plan.Refused("the calendar pack has no windows for the filing frequency ${sorted.last().frequency}")
            }

            sorted.first().effectiveFrom.isAfter(registration) -> {
                Plan.Invalid(
                    "the first filing frequency is effective ${sorted.first().effectiveFrom}, after the registration " +
                        "start date $registration",
                )
            }

            deregistrationDate != null && deregistrationDate.isBefore(registration) -> {
                Plan.Invalid("the registration end date $deregistrationDate is before the registration start date")
            }

            else -> {
                null
            }
        }

    private fun generate(
        entity: EntityRecord,
        sorted: List<FrequencyEntry>,
        inputs: Inputs,
    ): Plan =
        try {
            val pack = inputs.pack
            val history =
                FrequencyHistory.of(sorted.map { FrequencyChange(it.effectiveFrom, FilingFrequency(it.frequency)) })
            val through = pack.windowsOf(FilingFrequency(sorted.last().frequency)).last().start
            val rule = rules.resolve(entity.groupRef, entity.ref, inputs.ruleOn)
            val request = PeriodRequest(inputs.registration, inputs.deregistrationDate, history, through, rule)
            Plan.Periods(
                PackPeriodGenerator(pack).generate(request).map {
                    StoredPeriod(
                        StoredPeriod.refOf(entity.id, it.start),
                        it.start,
                        it.end,
                        it.returnForm,
                        it.dueDate,
                        it.kinds,
                    )
                },
            )
        } catch (e: CalendarPackCoverageException) {
            Plan.Refused(e.message ?: "the calendar pack does not cover the entity's periods")
        }
}
