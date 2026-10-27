package com.dynokore.gst.notification.domain.alerting

import java.time.LocalDate
import java.util.UUID

/** Payload of the event PrivacyNoticeDue (INV-REQ-1403 behavior 3). */
data class PrivacyNoticeDue(
    val caseId: String,
    val requestKind: PrivacyRequestKind,
    val receivedOn: LocalDate,
    val responseDeadline: LocalDate,
    val noticeLeadDays: Int,
) {
    init {
        require(isLowerCaseUuid(caseId)) { "caseId must be a lower-case UUID text" }
        require(noticeLeadDays >= 1) { "noticeLeadDays must be at least 1" }
        require(!responseDeadline.isBefore(receivedOn)) { "responseDeadline must not be before receivedOn" }
    }

    private fun isLowerCaseUuid(text: String): Boolean =
        try {
            UUID.fromString(text).toString() == text
        } catch (_: IllegalArgumentException) {
            false
        }
}
