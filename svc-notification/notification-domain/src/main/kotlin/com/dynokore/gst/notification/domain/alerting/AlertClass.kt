package com.dynokore.gst.notification.domain.alerting

/** Alert classes of the notification contract (AlertPolicy.alertClass). */
enum class AlertClass {
    COVERAGE,
    DEADLINE,
    BACKLOG,
    MISSING_ACKNOWLEDGEMENT,
    PACK_STALENESS,
    SECURITY,
    ;

    companion object {
        /** The member named exactly [key]; any other string (case, plural, empty) is refused. */
        fun fromKey(key: String): AlertClass =
            entries.firstOrNull { it.name == key }
                ?: throw IllegalArgumentException("Unknown alert class: '$key'")
    }
}

const val POL_OUT_OF_BOUNDS = "POL-013"
const val POL_CONSTRAINT = "POL-014"
const val POL_NOT_DISABLEABLE = "POL-015"
const val POL_BACK_DATED = "POL-022"
const val MAX_SNOOZE_DAYS = 7L

internal val HARD_STOP_CLASSES = setOf(AlertClass.DEADLINE, AlertClass.COVERAGE)
