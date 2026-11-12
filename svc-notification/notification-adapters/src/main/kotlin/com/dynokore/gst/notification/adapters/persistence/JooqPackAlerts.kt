package com.dynokore.gst.notification.adapters.persistence

import com.dynokore.gst.notification.adapters.persistence.jooq.tables.Alert.Companion.ALERT
import com.dynokore.gst.notification.application.alerting.PackAlertStore
import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.gst.notification.domain.alerting.PACK_ALERT_TRIGGER
import com.dynokore.gst.notification.domain.alerting.PackAlertDraft
import com.dynokore.gst.notification.domain.alerting.TENANT_WIDE_ENTITY_REF
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Raises and resolves the one PACK_STALENESS alert of a tenant (INV-REQ-1080, INV-Q-20864 A). The raise conflict
 * target is the open-alert unique index alert_one_open_per_key (expression columns and the open-state predicate), so a
 * repeated raise is an answer (false), never an exception. The resolve needs the column grant on state and
 * snoozed_until only. Forced row level security binds both to the tenant.
 */
@Component
class JooqPackAlerts(
    private val dsl: DSLContext,
    private val clock: Clock,
) : PackAlertStore {
    override fun raise(
        tenant: TenantId,
        draft: PackAlertDraft,
    ): Boolean =
        dsl
            .insertInto(ALERT)
            .set(ALERT.TENANT_ID, tenant.value)
            .set(ALERT.ALERT_ID, UUID.randomUUID())
            .set(ALERT.ENTITY_REF, draft.entityRef)
            .set(ALERT.ALERT_CLASS, draft.alertClass.name)
            .set(ALERT.STATE, RAISED)
            .set(ALERT.RAISED_AT, OffsetDateTime.now(clock))
            .set(ALERT.TRIGGER, draft.trigger)
            .set(ALERT.CURRENT_TIER, draft.tier)
            .onConflict(
                ALERT.TENANT_ID,
                ALERT.ENTITY_REF,
                ALERT.ALERT_CLASS,
                DSL.coalesce(ALERT.TRIGGER, ""),
                DSL.coalesce(ALERT.PERIOD_REF, ""),
            ).where(ALERT.STATE.`in`(RAISED, SNOOZED))
            .doNothing()
            .execute() == 1

    override fun resolveOpen(tenant: TenantId): Boolean =
        dsl
            .update(ALERT)
            .set(ALERT.STATE, RESOLVED)
            .setNull(ALERT.SNOOZED_UNTIL)
            .where(ALERT.TENANT_ID.eq(tenant.value))
            .and(ALERT.ALERT_CLASS.eq(AlertClass.PACK_STALENESS.name))
            .and(ALERT.TRIGGER.eq(PACK_ALERT_TRIGGER))
            .and(ALERT.ENTITY_REF.eq(TENANT_WIDE_ENTITY_REF))
            .and(ALERT.STATE.`in`(RAISED, ACKNOWLEDGED, SNOOZED))
            .execute() > 0

    private companion object {
        const val RAISED = "RAISED"
        const val ACKNOWLEDGED = "ACKNOWLEDGED"
        const val SNOOZED = "SNOOZED"
        const val RESOLVED = "RESOLVED"
    }
}
