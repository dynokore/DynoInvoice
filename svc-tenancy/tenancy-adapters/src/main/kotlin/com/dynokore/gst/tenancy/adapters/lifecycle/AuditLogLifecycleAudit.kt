package com.dynokore.gst.tenancy.adapters.lifecycle

import com.dynokore.gst.tenancy.adapters.events.EventContexts
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * The audit entry of a suspension (INV-REQ-963 b2, NIST SP 800-53 AU-3), written through audit-log in the request's
 * transaction: the actor is the user who acted, never a role (INV-Q-10647); the reason is stored as given.
 */
class AuditLogLifecycleAudit(
    private val writer: AuditWriter,
    private val tenants: TenantContext,
) {
    fun suspended(
        actor: String,
        entityRef: String,
        reason: String,
    ) {
        val entry = AuditEntry(actor = actor, action = SUSPENDED, scope = entityRef, reason = reason)
        writer.record(entry, EventContexts.forAggregate(tenants.current(), EntityRef(entityRef), entityRef))
    }

    companion object {
        const val SUSPENDED = "entity.suspended"
    }
}
