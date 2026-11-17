package com.dynokore.gst.access.domain.consultant

import java.time.LocalDate

/**
 * The period of an `implementation.consultant` grant after go-live (INV-REQ-2465, VP-69): 30 days by default, never
 * more than the platform maximum of 120 days (owner-confirmed values, INV-Q-1891). While VP-69 is planned only the
 * default is accepted.
 */
object ConsultantGrantPeriod {
    const val DEFAULT_DAYS = 30
    const val MAX_DAYS = 120

    sealed interface Choice {
        data class Days(
            val value: Int,
        ) : Choice

        /** Above the platform maximum, or not a positive number of days (INV-AC-2494). */
        data object OutOfRange : Choice

        /** VP-69 is planned and the period is not the default (INV-AC-2512). */
        data object NotAvailable : Choice
    }

    fun choose(
        requested: Int?,
        vp69Available: Boolean,
    ): Choice {
        val days = requested ?: DEFAULT_DAYS
        return when {
            days !in 1..MAX_DAYS -> Choice.OutOfRange
            !vp69Available && days != DEFAULT_DAYS -> Choice.NotAvailable
            else -> Choice.Days(days)
        }
    }

    /** The day the grant ends: go-live plus [days] calendar days. */
    fun validTo(
        goLive: LocalDate,
        days: Int,
    ): LocalDate = goLive.plusDays(days.toLong())
}
