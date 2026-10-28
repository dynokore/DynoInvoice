package com.dynokore.gst.tenancy.adapters.election

import com.dynokore.gst.tenancy.adapters.events.EventContexts
import com.dynokore.gst.tenancy.application.election.ApprovedChange
import com.dynokore.gst.tenancy.application.election.ElectionAudit
import com.dynokore.gst.tenancy.application.election.PendingChange
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * The "requested" and "approved" audit entries of an election change, written through audit-log in the request's
 * transaction (INV-REQ-1226): the actor is the user who acted, never a role (INV-Q-10647).
 */
class AuditLogElectionAudit(
    private val writer: AuditWriter,
    private val tenants: TenantContext,
) : ElectionAudit {
    override fun requested(change: PendingChange) {
        record(
            AuditEntry(
                actor = change.requestedBy,
                action = REQUESTED,
                scope = change.entityRef,
                reason = "${change.kind.variationPoint} change ${change.changeRef} requested to ${describe(change)}",
            ),
            change.entityRef,
        )
    }

    override fun approved(approval: ApprovedChange) {
        val change = approval.change
        record(
            AuditEntry(
                actor = approval.approvedBy,
                action = APPROVED,
                scope = change.entityRef,
                reason =
                    "${change.kind.variationPoint} change ${change.changeRef} to ${describe(change)} approved by " +
                        "${approval.approvedBy}, requested by ${change.requestedBy}",
            ),
            change.entityRef,
        )
    }

    private fun describe(change: PendingChange): String =
        "${change.value}${change.basis?.let { "/$it" }.orEmpty()} effective ${change.effectiveFrom}"

    private fun record(
        entry: AuditEntry,
        entityRef: String,
    ) {
        writer.record(entry, EventContexts.forAggregate(tenants.current(), EntityRef(entityRef), entityRef))
    }

    companion object {
        const val REQUESTED = "election-change.requested"
        const val APPROVED = "election-change.approved"
    }
}
