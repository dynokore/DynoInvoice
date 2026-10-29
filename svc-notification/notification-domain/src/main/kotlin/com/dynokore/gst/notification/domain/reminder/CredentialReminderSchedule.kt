package com.dynokore.gst.notification.domain.reminder

import com.dynokore.gst.notification.domain.alerting.POL_OUT_OF_BOUNDS
import java.time.LocalDate

/** Limits of VP-68 (days before expiry); every number arrives as an argument. */
data class ReminderPolicy(
    val minDays: Int,
    val maxDays: Int,
    val maxItems: Int,
    val minLeadDays: Int,
) {
    init {
        require(minDays >= 1) { "minDays must be at least 1" }
        require(maxDays >= minDays) { "maxDays must be at least minDays" }
        require(maxItems >= 1) { "maxItems must be at least 1" }
        require(minLeadDays in minDays..maxDays) { "minLeadDays must lie between minDays and maxDays" }
    }
}

/** Days before expiry on which a reminder goes out; never empty. */
data class ReminderSchedule(
    val daysBefore: List<Int>,
) {
    init {
        require(daysBefore.isNotEmpty()) { "A reminder schedule is never empty" }
    }
}

sealed interface ScheduleDecision {
    data class Accepted(
        val schedule: ReminderSchedule,
    ) : ScheduleDecision

    data class Refused(
        val code: String,
        val keeps: ReminderSchedule,
    ) : ScheduleDecision
}

object CredentialReminderSchedule {
    /** Accepts [requested] as a set of offsets (sorted descending) or refuses and keeps [current]. */
    fun set(
        requested: List<Int>,
        current: ReminderSchedule,
        policy: ReminderPolicy,
    ): ScheduleDecision {
        val valid =
            requested.isNotEmpty() &&
                requested.size <= policy.maxItems &&
                requested.toSet().size == requested.size &&
                requested.all { it in policy.minDays..policy.maxDays } &&
                requested.any { it >= policy.minLeadDays }
        return if (valid) {
            ScheduleDecision.Accepted(ReminderSchedule(requested.sortedDescending()))
        } else {
            ScheduleDecision.Refused(POL_OUT_OF_BOUNDS, current)
        }
    }

    /** Calendar-day reminder dates for [expiry], ascending. */
    fun dates(
        expiry: LocalDate,
        schedule: ReminderSchedule,
    ): List<LocalDate> = schedule.daysBefore.map { expiry.minusDays(it.toLong()) }.sorted()
}
