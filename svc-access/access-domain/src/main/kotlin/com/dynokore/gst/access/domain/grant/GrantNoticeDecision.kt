package com.dynokore.gst.access.domain.grant

import java.time.LocalDate

/** The notices to send now and the passed ones to record superseded, for the real [expiryDate]. */
data class NoticeDecision(
    val expiryDate: LocalDate,
    val send: List<ExpiryNotice>,
    val superseded: List<ExpiryNotice>,
)

/** Catch-up decision of INV-Q-13245 (A): pure over explicit inputs, no clock. */
object GrantNoticeDecision {
    /**
     * Of the pending notices (days not in [recorded]) that are due on [today], the latest is sent and the earlier
     * ones are superseded, ascending by notice date. Notices dated after [today] appear in neither list.
     */
    fun decide(
        schedule: NoticeSchedule,
        today: LocalDate,
        recorded: Set<Int>,
    ): NoticeDecision {
        val due =
            schedule.notices
                .filter { it.daysBefore !in recorded && !it.noticeDate.isAfter(today) }
                .sortedBy { it.noticeDate }
        return NoticeDecision(schedule.expiryDate, due.takeLast(1), due.dropLast(1))
    }
}
