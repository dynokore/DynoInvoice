package com.dynokore.gst.notification.domain.alerting

/** POL-008: a directed field is looser than the value of the same row at a broader scope (tighten-only). */
const val POL_LOOSER_THAN_DEFAULT = "POL-008"

enum class ThresholdRefusal {
    LOOSER_THAN_DEFAULT,
}

sealed interface ThresholdDecision {
    data class Accepted(
        val thresholdDays: Int,
    ) : ThresholdDecision

    data class Refused(
        val code: String,
        val reason: ThresholdRefusal,
    ) : ThresholdDecision
}

/** Inclusive range of days before the due date; installation configuration, tighten-only against its defaults. */
data class ThresholdBounds(
    val minDays: Int,
    val maxDays: Int,
) {
    init {
        require(minDays <= maxDays) { "minDays $minDays is above maxDays $maxDays" }
    }

    companion object {
        fun tightenedFrom(
            defaults: ThresholdBounds,
            configured: ThresholdBounds,
        ): ThresholdBounds {
            require(configured.minDays >= defaults.minDays && configured.maxDays <= defaults.maxDays) {
                "configured bounds $configured are wider than the default bounds $defaults"
            }
            return configured
        }
    }
}

/** INV-REQ-1117: the first deadline alert may be earlier than the calendar-pack default, never later. */
object ThresholdRule {
    fun decide(
        thresholdDays: Int,
        calendarPackDefaultDays: Int,
        bounds: ThresholdBounds,
    ): ThresholdDecision {
        require(thresholdDays in bounds.minDays..bounds.maxDays) { "thresholdDays $thresholdDays is outside $bounds" }
        require(calendarPackDefaultDays in bounds.minDays..bounds.maxDays) {
            "calendarPackDefaultDays $calendarPackDefaultDays is outside $bounds"
        }
        return if (thresholdDays < calendarPackDefaultDays) {
            ThresholdDecision.Refused(POL_LOOSER_THAN_DEFAULT, ThresholdRefusal.LOOSER_THAN_DEFAULT)
        } else {
            ThresholdDecision.Accepted(thresholdDays)
        }
    }
}
