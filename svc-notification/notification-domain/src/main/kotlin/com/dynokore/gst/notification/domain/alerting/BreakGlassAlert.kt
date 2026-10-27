package com.dynokore.gst.notification.domain.alerting

/** Why no alert is raised. */
enum class BreakGlassWithheld {
    UNSUPPORTED_CODE,
    NO_RECIPIENT,
}

/** The one alert to store: every recipient at once, no tier (INV-AC-2384). */
data class BreakGlassAlertDraft(
    val alertClass: String,
    val trigger: String,
    val entityRef: String,
    val recipients: List<String>,
    val tiers: List<Int>,
)

sealed interface BreakGlassDecision {
    data class Raise(
        val draft: BreakGlassAlertDraft,
    ) : BreakGlassDecision

    /**
     * No alert now. Neither [BreakGlassWithheld] reason is an acceptable end state: the caller must retry
     * or dead-letter the event and never drop it (INV-REQ-910 must_not_happen: an alert class with no recipient).
     */
    data class Withhold(
        val reason: BreakGlassWithheld,
    ) : BreakGlassDecision
}

/** Decides the break-glass alert (INV-Q-8966 A, INV-REQ-897). Reads only its arguments; keeps no state. */
object BreakGlassAlert {
    fun decide(
        event: BreakGlassAlertRaised,
        recipients: List<String>,
    ): BreakGlassDecision {
        val distinct = recipients.filter { it.isNotBlank() }.distinct()
        return when {
            event.accCode != ACC_BREAK_GLASS -> {
                BreakGlassDecision.Withhold(BreakGlassWithheld.UNSUPPORTED_CODE)
            }

            distinct.isEmpty() -> {
                BreakGlassDecision.Withhold(BreakGlassWithheld.NO_RECIPIENT)
            }

            else -> {
                BreakGlassDecision.Raise(
                    BreakGlassAlertDraft(
                        alertClass = CLASS_SECURITY,
                        trigger = TRIGGER_BREAK_GLASS_USED,
                        entityRef = TENANT_WIDE_ENTITY_REF,
                        recipients = distinct,
                        tiers = emptyList(),
                    ),
                )
            }
        }
    }
}
