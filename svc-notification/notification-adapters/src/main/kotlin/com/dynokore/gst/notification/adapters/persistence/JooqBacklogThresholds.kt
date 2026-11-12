package com.dynokore.gst.notification.adapters.persistence

import com.dynokore.gst.notification.adapters.persistence.jooq.tables.AlertPolicy.Companion.ALERT_POLICY
import com.dynokore.gst.notification.application.alerting.BacklogThresholdSource
import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.gst.notification.domain.alerting.BacklogThresholds
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.springframework.stereotype.Component

/**
 * Reads the four backlog thresholds of notification.alert_policy (INV-REQ-1370). Forced row level security limits the
 * read to the bound tenant, so the tenant is not a query argument. A null column is a null member, no row is null:
 * nothing is defaulted.
 */
@Component
class JooqBacklogThresholds(
    private val dsl: DSLContext,
) : BacklogThresholdSource {
    override fun thresholds(
        tenant: TenantId,
        entityRef: String,
        alertClass: AlertClass,
    ): BacklogThresholds? =
        dsl
            .select(
                ALERT_POLICY.MAX_QUEUE_DEPTH,
                ALERT_POLICY.MAX_OLDEST_UNSENT_AGE_SECONDS,
                ALERT_POLICY.MAX_FAILURE_RATIO,
                ALERT_POLICY.MAX_UNACKNOWLEDGED_COUNT,
            ).from(ALERT_POLICY)
            .where(ALERT_POLICY.ENTITY_REF.eq(entityRef))
            .and(ALERT_POLICY.ALERT_CLASS.eq(alertClass.name))
            .fetchOne()
            ?.let {
                BacklogThresholds(
                    it.value1()?.toLong(),
                    it.value2(),
                    it.value3()?.toDouble(),
                    it.value4()?.toLong(),
                )
            }
}
