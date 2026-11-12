package com.dynokore.gst.notification.adapters.persistence

import com.dynokore.gst.notification.adapters.persistence.jooq.tables.Alert.Companion.ALERT
import com.dynokore.gst.notification.application.alerting.BacklogAlertDraft
import com.dynokore.gst.notification.application.alerting.BacklogAlertStore
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.ZoneOffset
import java.util.UUID

/**
 * Inserts a RAISED row of notification.alert (INV-REQ-1370). The conflict target is the open-alert unique index
 * alert_one_open_per_key (expression columns and the open-state predicate), so a repeated key is an answer (false),
 * never an exception. Forced row level security refuses a tenant other than the bound one.
 */
@Component
class JooqBacklogAlerts(
    private val dsl: DSLContext,
) : BacklogAlertStore {
    override fun raise(draft: BacklogAlertDraft): Boolean =
        dsl
            .insertInto(ALERT)
            .set(ALERT.TENANT_ID, draft.tenant.value)
            .set(ALERT.ALERT_ID, UUID.randomUUID())
            .set(ALERT.ENTITY_REF, draft.entityRef)
            .set(ALERT.ALERT_CLASS, draft.alertClass.name)
            .set(ALERT.STATE, RAISED)
            .set(ALERT.RAISED_AT, draft.raisedAt.atOffset(ZoneOffset.UTC))
            .set(ALERT.TRIGGER, draft.trigger)
            .set(ALERT.PERIOD_REF, draft.periodRef)
            .onConflict(
                ALERT.TENANT_ID,
                ALERT.ENTITY_REF,
                ALERT.ALERT_CLASS,
                DSL.coalesce(ALERT.TRIGGER, ""),
                DSL.coalesce(ALERT.PERIOD_REF, ""),
            ).where(ALERT.STATE.`in`(RAISED, SNOOZED))
            .doNothing()
            .execute() == 1

    private companion object {
        const val RAISED = "RAISED"
        const val SNOOZED = "SNOOZED"
    }
}
