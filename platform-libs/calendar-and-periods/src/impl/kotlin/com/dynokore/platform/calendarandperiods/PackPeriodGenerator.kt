package com.dynokore.platform.calendarandperiods

import java.time.LocalDate

/**
 * [PeriodGenerator] over a calendar pack (INV-REQ-83, INV-REQ-84, INV-REQ-688, INV-REQ-707).
 *
 * Starting at the registration date it takes, for the frequency in effect, the pack window that holds the cursor:
 *  - a cursor inside a window gives a short period starting there (first at registration, else transitional);
 *  - a frequency change inside the window gives a transitional period, cut as the pack's [TransitionStyle] says;
 *  - the deregistration date ends the last period, which is final and uses the pack's final return form.
 * Every start and end is a pack window boundary, a change date or the deregistration date: none is computed from a
 * frequency.
 */
class PackPeriodGenerator(
    private val pack: CalendarPack,
    private val dueDates: DueDates = PackDueDates(pack),
) : PeriodGenerator {
    override fun generate(request: PeriodRequest): List<PeriodRecord> {
        val periods = mutableListOf<PeriodRecord>()
        val last = request.deregistrationDate
        var cursor = request.registrationDate
        while (!cursor.isAfter(request.through) && (last == null || !cursor.isAfter(last))) {
            val period = nextPeriod(request, cursor)
            periods += period
            cursor = period.end.plusDays(1)
        }
        return periods
    }

    private fun nextPeriod(
        request: PeriodRequest,
        cursor: LocalDate,
    ): PeriodRecord {
        val frequency =
            request.history.at(cursor)
                ?: throw CalendarPackCoverageException("no filing frequency in effect on $cursor")
        val window = pack.windowContaining(frequency, cursor)
        val kinds = mutableSetOf<PeriodKind>()
        var end = window.end
        var form = window.returnForm
        if (cursor != window.start) {
            kinds += if (cursor == request.registrationDate) PeriodKind.FIRST else PeriodKind.TRANSITIONAL
        }
        val change = request.history.firstChangeIn(cursor, window.end)
        if (change != null) {
            kinds += PeriodKind.TRANSITIONAL
            end =
                when (pack.transition) {
                    TransitionStyle.CUT_AT_CHANGE -> {
                        change.effectiveFrom.minusDays(1)
                    }

                    TransitionStyle.EXTEND_TO_NEW_WINDOW -> {
                        pack.windowContaining(change.frequency, change.effectiveFrom).end
                    }
                }
        }
        val deregistration = request.deregistrationDate
        if (deregistration != null && !deregistration.isAfter(end)) {
            kinds += PeriodKind.FINAL
            end = deregistration
            form = pack.finalReturnForm
        }
        val due = dueDates.derive(end, form, request.nonBusinessDayRule)
        return PeriodRecord(cursor, end, form, due.adjusted, due.unadjusted, kinds)
    }
}
