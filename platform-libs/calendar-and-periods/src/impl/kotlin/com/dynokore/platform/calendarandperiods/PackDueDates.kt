package com.dynokore.platform.calendarandperiods

import java.time.LocalDate

/** [DueDates] over a calendar pack: the due rule, weekend, holidays and pinned rule all come from the pack. */
class PackDueDates(
    private val pack: CalendarPack,
) : DueDates {
    override fun derive(
        periodEnd: LocalDate,
        returnForm: String,
        entityRule: NonBusinessDayRule,
    ): DerivedDueDate {
        val unadjusted = pack.dueRuleFor(returnForm).applyTo(periodEnd)
        val rule = pack.effectiveRule(entityRule)
        val adjusted =
            when (rule) {
                NonBusinessDayRule.NO_MOVE -> unadjusted
                NonBusinessDayRule.NEXT_WORKING_DAY -> pack.calendar.nextBusinessDay(unadjusted)
            }
        return DerivedDueDate(unadjusted, adjusted, rule)
    }
}
