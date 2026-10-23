package com.dynokore.gst.access.application.alert

/**
 * Raises an entity-scoped ACC alert (ACC-001, ACC-003, ACC-004) as the domain event AccessAlertRaised in the command
 * transaction. ACC-002 does not travel here: break-glass raises it as the platform event BreakGlassAlertRaised.
 */
fun interface AccessAlerts {
    fun raise(
        code: String,
        scope: String,
    )
}
