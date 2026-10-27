package com.dynokore.gst.notification.domain.alerting

sealed interface DisableDecision {
    data object Allowed : DisableDecision

    data class Refused(
        val code: String,
    ) : DisableDecision
}

/** INV-REQ-1114: a deadline or coverage policy keeps at least one channel (an empty list is a disable). */
object DisableRule {
    fun decide(
        alertClass: AlertClass,
        channels: List<String>,
    ): DisableDecision =
        if (channels.isEmpty() && alertClass in HARD_STOP_CLASSES) {
            DisableDecision.Refused(POL_NOT_DISABLEABLE)
        } else {
            DisableDecision.Allowed
        }
}
