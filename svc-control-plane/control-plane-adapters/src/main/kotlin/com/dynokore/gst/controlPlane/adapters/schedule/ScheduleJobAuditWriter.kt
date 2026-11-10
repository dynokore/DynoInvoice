package com.dynokore.gst.controlPlane.adapters.schedule

import com.dynokore.gst.controlPlane.application.schedule.OpenDueTenants
import com.dynokore.gst.controlPlane.application.schedule.ScheduleJobAudit
import com.dynokore.gst.controlPlane.domain.schedule.TenantSchedule
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.util.UUID

/**
 * The audit entry of a schedule step that no person performs (INV-BR-1894): the actor is the scheduled job, the time is
 * the step's own, and the reason names the tenant and the contract reference. It is written through the service's
 * [AuditWriter] in the open transaction of the step, so it commits with the step or not at all.
 */
class ScheduleJobAuditWriter(
    private val writer: AuditWriter,
) : ScheduleJobAudit {
    override fun record(
        schedule: TenantSchedule,
        action: String,
        at: Instant,
    ) {
        val id = UUID.randomUUID().toString()
        writer.record(
            AuditEntry(
                actor = OpenDueTenants.ACTOR,
                action = action,
                scope = "installation",
                reason = "tenant=${schedule.tenantRef.value} contract=${schedule.contractReference.value} at=$at",
            ),
            EventContext(
                tenant = TenantId.PLATFORM,
                entity = EntityRef.PLATFORM,
                docUuid = null,
                transactionDate = null,
                aggregateKey = schedule.scheduleRef,
                aggregateVersion = null,
                correlation = CorrelationId(id),
                causation = CausationId(id),
            ),
        )
    }
}
