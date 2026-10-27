package com.dynokore.gst.notification.domain.alerting

import java.time.LocalDate

/** The message parameters; no field holds invoice content. */
data class AccreditationMessageParameters(
    val template: String,
    val reason: AccreditationReason,
    val taskRef: String,
    val releaseRef: String?,
    val currentUntil: LocalDate?,
)

object AccreditationMessage {
    fun of(event: AccreditationAlertRaised) =
        AccreditationMessageParameters(
            template = ACCREDITATION_ALERT_TEMPLATE,
            reason = event.reason,
            taskRef = event.taskRef,
            releaseRef = event.releaseRef,
            currentUntil = event.currentUntil,
        )
}
