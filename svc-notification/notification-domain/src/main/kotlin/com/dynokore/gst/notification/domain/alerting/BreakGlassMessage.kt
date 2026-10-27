package com.dynokore.gst.notification.domain.alerting

/** The message parameters; no field holds a credential or invoice content. */
data class BreakGlassMessageParameters(
    val template: String,
    val scope: BreakGlassScope,
    val action: BreakGlassAction,
    val unlockRef: String?,
)

object BreakGlassMessage {
    fun of(event: BreakGlassAlertRaised) =
        BreakGlassMessageParameters(
            template = BREAK_GLASS_TEMPLATE,
            scope = event.scope,
            action = event.action,
            unlockRef = event.unlockRef,
        )
}
