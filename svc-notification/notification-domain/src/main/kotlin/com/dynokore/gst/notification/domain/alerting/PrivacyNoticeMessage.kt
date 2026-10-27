package com.dynokore.gst.notification.domain.alerting

import java.time.LocalDate

/** The message parameters; no field holds a subject or a request reference. */
data class PrivacyNoticeMessageParameters(
    val template: String,
    val caseId: String,
    val requestKind: PrivacyRequestKind,
    val responseDeadline: LocalDate,
)

object PrivacyNoticeMessage {
    fun of(notice: PrivacyNoticeDue) =
        PrivacyNoticeMessageParameters(
            template = PRIVACY_NOTICE_TEMPLATE,
            caseId = notice.caseId,
            requestKind = notice.requestKind,
            responseDeadline = notice.responseDeadline,
        )
}
