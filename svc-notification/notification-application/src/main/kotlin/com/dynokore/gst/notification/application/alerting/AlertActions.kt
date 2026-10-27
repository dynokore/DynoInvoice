package com.dynokore.gst.notification.application.alerting

/**
 * The scoped permission keys `AuthorityReplica.decide` is asked for (INV-Q-10027). Neither is in the role-permission
 * catalogue yet (INV-Q-11676); the names follow the catalogue key `audit-entry.purge`.
 */
object AlertActions {
    const val SET_ALERT_POLICY = "alert-policy.set"
    const val SNOOZE_ALERT = "alert.snooze"
}
