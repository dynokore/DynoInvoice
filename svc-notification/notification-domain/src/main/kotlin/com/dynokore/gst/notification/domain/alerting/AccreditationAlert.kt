package com.dynokore.gst.notification.domain.alerting

/** Why no alert is raised. */
enum class AccreditationWithheld {
    ALREADY_ALERTED,
    NO_RECIPIENT,
}

/** The one alert to store: every recipient at once, no tier, no snooze. */
data class AccreditationAlertDraft(
    val alertClass: String,
    val trigger: String,
    val entityRef: String,
    val alertRef: String,
    val taskRef: String,
    val recipients: List<String>,
    val tiers: List<Int>,
    val channel: AccreditationChannel,
)

enum class AccreditationChannel { EMAIL }

sealed interface AccreditationDecision {
    data class Raise(
        val draft: AccreditationAlertDraft,
    ) : AccreditationDecision

    /**
     * No alert now. [AccreditationWithheld.NO_RECIPIENT] is not an acceptable end state: the caller must retry
     * or dead-letter the event and never drop it (INV-REQ-910 must_not_happen: an alert class with no recipient).
     */
    data class Withhold(
        val reason: AccreditationWithheld,
    ) : AccreditationDecision
}

/** Decides the accreditation alert (INV-Q-7316 A, INV-REQ-2657). Reads only its arguments; keeps no state. */
object AccreditationAlert {
    fun decide(
        event: AccreditationAlertRaised,
        recipients: List<String>,
        alertedRefs: Set<String>,
    ): AccreditationDecision {
        val distinct = recipients.filter { it.isNotBlank() }.distinct()
        return when {
            event.alertRef in alertedRefs -> {
                AccreditationDecision.Withhold(AccreditationWithheld.ALREADY_ALERTED)
            }

            distinct.isEmpty() -> {
                AccreditationDecision.Withhold(AccreditationWithheld.NO_RECIPIENT)
            }

            else -> {
                AccreditationDecision.Raise(
                    AccreditationAlertDraft(
                        alertClass = CLASS_ACCREDITATION,
                        trigger = event.reason.name,
                        entityRef = PLATFORM_ENTITY_REF,
                        alertRef = event.alertRef,
                        taskRef = event.taskRef,
                        recipients = distinct,
                        tiers = emptyList(),
                        channel = AccreditationChannel.EMAIL,
                    ),
                )
            }
        }
    }
}
