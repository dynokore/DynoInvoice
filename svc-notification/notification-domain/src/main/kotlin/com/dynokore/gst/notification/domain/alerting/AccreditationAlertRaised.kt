package com.dynokore.gst.notification.domain.alerting

import java.time.Instant
import java.time.LocalDate

private const val MAX_REF = 128

/** Payload of the event AccreditationAlertRaised. */
data class AccreditationAlertRaised(
    val alertRef: String,
    val taskRef: String,
    val reason: AccreditationReason,
    val raisedAt: Instant,
    val releaseRef: String?,
    val currentUntil: LocalDate?,
) {
    init {
        requireRef("alertRef", alertRef)
        requireRef("taskRef", taskRef)
        releaseRef?.let { requireRef("releaseRef", it) }
    }

    private fun requireRef(
        name: String,
        value: String,
    ) = require(value.isNotBlank() && value.length <= MAX_REF) { "$name must be 1..$MAX_REF characters and not blank" }
}
