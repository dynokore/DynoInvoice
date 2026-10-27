package com.dynokore.gst.notification.domain.alerting

/** Why no alert is raised. */
enum class PrivacyNoticeWithheld {
    ALREADY_ALERTED,
    NO_RECIPIENT,
}

/** The one alert to store: every recipient at once, no tier (INV-REQ-1113 behavior 2). */
data class PrivacyNoticeAlertDraft(
    val alertClass: String,
    val trigger: String,
    val entityRef: String,
    val caseId: String,
    val recipients: List<String>,
    val tiers: List<Int>,
)

sealed interface PrivacyNoticeDecision {
    data class Raise(
        val draft: PrivacyNoticeAlertDraft,
    ) : PrivacyNoticeDecision

    /**
     * No alert now. [PrivacyNoticeWithheld.NO_RECIPIENT] is not an acceptable end state: the caller must retry
     * or dead-letter the event and never drop it (INV-REQ-910 must_not_happen: an alert class with no recipient).
     */
    data class Withhold(
        val reason: PrivacyNoticeWithheld,
    ) : PrivacyNoticeDecision
}

/** Decides the privacy notice security alert (INV-Q-6851 A, INV-REQ-1403). Reads only its arguments; keeps no state. */
object PrivacyNoticeAlert {
    fun decide(
        notice: PrivacyNoticeDue,
        recipients: List<String>,
        alertedCaseIds: Set<String>,
    ): PrivacyNoticeDecision {
        val distinct = recipients.filter { it.isNotBlank() }.distinct()
        return when {
            notice.caseId in alertedCaseIds -> {
                PrivacyNoticeDecision.Withhold(PrivacyNoticeWithheld.ALREADY_ALERTED)
            }

            distinct.isEmpty() -> {
                PrivacyNoticeDecision.Withhold(PrivacyNoticeWithheld.NO_RECIPIENT)
            }

            else -> {
                PrivacyNoticeDecision.Raise(
                    PrivacyNoticeAlertDraft(
                        alertClass = CLASS_SECURITY,
                        trigger = TRIGGER_PRIVACY_NOTICE_DUE,
                        entityRef = TENANT_WIDE_ENTITY_REF,
                        caseId = notice.caseId,
                        recipients = distinct,
                        tiers = emptyList(),
                    ),
                )
            }
        }
    }
}
