package com.dynokore.platform.calendarandperiods

import java.time.LocalDate

/**
 * A filing frequency, named by the calendar pack (for example one code per cycle the pack defines windows for).
 * It is an identifier only: it carries no length. A period's start and end come from the pack's windows and from the
 * entity's dated history, never from a duration derived from a frequency (INV-REQ-707).
 */
@JvmInline
value class FilingFrequency(
    val code: String,
) {
    init {
        require(code.isNotBlank()) { "a filing frequency code is not blank" }
    }
}

/** One entry of an entity's effective-dated filing frequency history (INV-NOTE-3#def:FR-AA.5). */
data class FrequencyChange(
    val effectiveFrom: LocalDate,
    val frequency: FilingFrequency,
)

/** The entity's filing frequency history: non-empty, one entry per effective date, in date order. */
class FrequencyHistory private constructor(
    val entries: List<FrequencyChange>,
) {
    /** The frequency in effect on [date], or null before the first entry. */
    fun at(date: LocalDate): FilingFrequency? = entries.lastOrNull { !it.effectiveFrom.isAfter(date) }?.frequency

    /** The first change effective after [after] and not after [through]: a change that falls inside a window. */
    fun firstChangeIn(
        after: LocalDate,
        through: LocalDate,
    ): FrequencyChange? = entries.firstOrNull { it.effectiveFrom.isAfter(after) && !it.effectiveFrom.isAfter(through) }

    companion object {
        fun of(entries: List<FrequencyChange>): FrequencyHistory {
            require(entries.isNotEmpty()) { "a filing frequency history has at least one entry" }
            val sorted = entries.sortedBy { it.effectiveFrom }
            require(sorted.map { it.effectiveFrom }.toSet().size == sorted.size) {
                "a filing frequency history has one entry per effective date"
            }
            return FrequencyHistory(sorted)
        }
    }
}

/**
 * Why a period is irregular (INV-REQ-84). A period with no kind is a standard window of the pack.
 * A period can carry several: a registration and a deregistration in one window give a first and a final period.
 */
enum class PeriodKind { FIRST, FINAL, TRANSITIONAL }

/**
 * A period record (INV-REQ-83): explicit start and end dates (both inclusive), the return form and the filing due
 * date. Period membership is a date-range test against the record (INV-REQ-707 b2). The record is unconfirmed until
 * `entity.admin` or `tax.approver` confirms it (`confirmPeriods`, INV-REQ-84 b5); the service holds that state.
 */
data class PeriodRecord(
    val start: LocalDate,
    val end: LocalDate,
    val returnForm: String,
    /** The filing due date after the non-business-day rule (INV-REQ-87). */
    val dueDate: LocalDate,
    /** The filing due date before the non-business-day rule moved it, if it did. */
    val unadjustedDueDate: LocalDate,
    val kinds: Set<PeriodKind>,
    val confirmed: Boolean = false,
) {
    init {
        require(!end.isBefore(start)) { "a period ends on or after its start" }
        require(returnForm.isNotBlank()) { "a period has a return form" }
    }

    /** True for a first, final or transitional period (INV-REQ-84 b1). */
    val irregular: Boolean get() = kinds.isNotEmpty()

    /** The period's length in days, stated by its own dates (INV-REQ-688). */
    val lengthInDays: Long get() = end.toEpochDay() - start.toEpochDay() + 1

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
}

/** What a caller asks for: the periods of one entity from registration to [through] (or to deregistration). */
data class PeriodRequest(
    val registrationDate: LocalDate,
    /** The last day of registration, when the entity deregistered (INV-REQ-84 b3). */
    val deregistrationDate: LocalDate?,
    val history: FrequencyHistory,
    /** Periods are generated while their start is on or before this date. */
    val through: LocalDate,
    /** The entity's non-business-day rule (VP-62), unless the pack pins one (INV-REQ-87). */
    val nonBusinessDayRule: NonBusinessDayRule,
) {
    init {
        require(deregistrationDate == null || !deregistrationDate.isBefore(registrationDate)) {
            "deregistration is on or after registration"
        }
        requireNotNull(history.at(registrationDate)) { "the filing frequency history covers the registration date" }
    }
}

/** The calendar pack does not cover a date the entity needs a period for, or has no rule for a return form. */
class CalendarPackCoverageException(
    message: String,
) : IllegalStateException(message)

/** Generates period records (INV-REQ-83, INV-REQ-84, INV-REQ-688). */
interface PeriodGenerator {
    fun generate(request: PeriodRequest): List<PeriodRecord>
}
