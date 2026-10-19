package com.dynokore.gst.access.adapters.audit

import com.dynokore.gst.access.adapters.events.EventContexts
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * Writes the audit entry of a grant change through audit-log (INV-REQ-861): actor, action, scope, reason and prior
 * state,
 * as an immutable row staged with the outbox of the command transaction; `evidence-svc` is the sole store
 * (INV-ADR-282).
 * There is no update path: audit-log exposes `record` only.
 */
class OutboxGrantAudit(
    private val writer: AuditWriter,
    private val tenants: TenantContext,
) : GrantAudit {
    override fun record(entry: GrantAuditEntry) {
        writer.record(
            AuditEntry(entry.actor, entry.action, entry.scope, entry.reason, entry.priorStateJson),
            EventContexts.forAggregate(tenants.current(), EntityRef.TENANT_WIDE, entry.scope),
        )
    }
}
