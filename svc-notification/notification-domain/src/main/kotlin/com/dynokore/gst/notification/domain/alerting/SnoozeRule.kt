package com.dynokore.gst.notification.domain.alerting

import java.time.Duration
import java.time.Instant

enum class SnoozeRefusal {
    NOT_IN_FUTURE,
    BEYOND_MAX_SNOOZE,
    BEYOND_HARD_STOP,
    HARD_STOP_UNKNOWN,
}

sealed interface SnoozeDecision {
    data class Accepted(
        val until: Instant,
    ) : SnoozeDecision

    data class Refused(
        val code: String,
        val reason: SnoozeRefusal,
    ) : SnoozeDecision
}

/** INV-REQ-1114: a snooze is in the future, at most 7 days, and for deadline and coverage not past the hard stop. */
object SnoozeRule {
    fun decide(
        alertClass: AlertClass,
        now: Instant,
        snoozeUntil: Instant,
        hardStop: Instant?,
    ): SnoozeDecision {
        val needsHardStop = alertClass in HARD_STOP_CLASSES
        return when {
            !snoozeUntil.isAfter(now) -> {
                SnoozeDecision.Refused(POL_OUT_OF_BOUNDS, SnoozeRefusal.NOT_IN_FUTURE)
            }

            snoozeUntil.isAfter(now.plus(Duration.ofDays(MAX_SNOOZE_DAYS))) -> {
                SnoozeDecision.Refused(POL_OUT_OF_BOUNDS, SnoozeRefusal.BEYOND_MAX_SNOOZE)
            }

            needsHardStop && hardStop == null -> {
                SnoozeDecision.Refused(POL_CONSTRAINT, SnoozeRefusal.HARD_STOP_UNKNOWN)
            }

            needsHardStop && snoozeUntil.isAfter(hardStop) -> {
                SnoozeDecision.Refused(POL_OUT_OF_BOUNDS, SnoozeRefusal.BEYOND_HARD_STOP)
            }

            else -> {
                SnoozeDecision.Accepted(snoozeUntil)
            }
        }
    }
}
