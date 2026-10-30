package com.dynokore.gst.access.domain.grant

import java.time.LocalDate
import java.time.ZoneId

/** One expiry notice: sent [daysBefore] days before the expiry date, on [noticeDate]. */
data class ExpiryNotice(
    val daysBefore: Int,
    val noticeDate: LocalDate,
) {
    init {
        require(daysBefore >= 1) { "a notice must go at least 1 day before expiry, was $daysBefore" }
    }
}

/** The notices of one grant, ordered by [ExpiryNotice.noticeDate] ascending. */
data class NoticeSchedule(
    val expiryDate: LocalDate,
    val notices: List<ExpiryNotice>,
)

/** Grant expiry notice dates (INV-REQ-893 behaviour 1, INV-REQ-1267): pure date arithmetic, no clock. */
object GrantExpiryNotices {
    /**
     * The notice dates of a grant, or null when the window is open-ended. The expiry date is the day of `until` in
     * [zone]; [days] and [minimumLeadDays] are the tenant's setting and the bound of INV-REQ-1267.
     */
    fun schedule(
        window: ValidityWindow,
        zone: ZoneId,
        days: List<Int>,
        minimumLeadDays: Int,
    ): NoticeSchedule? {
        require(minimumLeadDays >= 1) { "the minimum lead must be at least 1 day, was $minimumLeadDays" }
        require(days.isNotEmpty()) { "the notice days must not be empty" }
        require(days.all { it >= 1 }) { "every notice day must be at least 1, was $days" }
        require(days.toSet().size == days.size) { "the notice days must not repeat a day, was $days" }
        require(days.max() >= minimumLeadDays) {
            "at least one notice must be $minimumLeadDays or more days ahead, was $days"
        }
        val until = window.until ?: return null
        val expiryDate = until.atZone(zone).toLocalDate()
        val notices =
            days
                .map { ExpiryNotice(it, expiryDate.minusDays(it.toLong())) }
                .sortedBy { it.noticeDate }
        return NoticeSchedule(expiryDate, notices)
    }
}
