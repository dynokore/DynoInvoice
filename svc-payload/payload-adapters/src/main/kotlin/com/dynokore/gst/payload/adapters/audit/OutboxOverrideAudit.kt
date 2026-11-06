package com.dynokore.gst.payload.adapters.audit

import com.dynokore.gst.payload.application.override.AuditTrail
import com.dynokore.gst.payload.application.override.DocumentVersions
import com.dynokore.gst.payload.application.override.StoredOverride
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Writes the audit entry of an override request or release through audit-log (INV-REQ-1180 behaviour 3, INV-Q-17172
 * A): `AuditWriter.record` stages one `AuditEntryRecorded` row in the outbox of the transaction the override store
 * uses, so the entry commits with the override or vanishes with it. [AuditEntry.actor] is the acting user ID, never a
 * role (INV-Q-10647). `AuditWriteFailed` propagates: the transaction rolls back. A version without stored facts has
 * no entity to name, so it is refused (fail closed) and nothing is written.
 */
class OutboxOverrideAudit(
    private val writer: AuditWriter,
    private val tenants: TenantContext,
    private val versions: DocumentVersions,
    private val mapper: ObjectMapper = ObjectMapper(),
) : AuditTrail {
    override fun requested(
        userId: String,
        override: StoredOverride,
    ) = write(userId, ACTION_REQUESTED, override, null)

    override fun released(
        userId: String,
        override: StoredOverride,
    ) = write(userId, ACTION_RELEASED, override, override.requestedBy)

    private fun write(
        userId: String,
        action: String,
        override: StoredOverride,
        firstApprover: String?,
    ) {
        val facts =
            checkNotNull(versions.find(override.docUuid, override.version)) {
                "no stored facts for the version of the override"
            }
        val prior =
            firstApprover?.let {
                mapper.writeValueAsString(mapper.createObjectNode().put(FIRST_APPROVER_FIELD, it))
            }
        writer.record(
            AuditEntry(
                actor = userId,
                action = action,
                scope = "${override.docUuid}:${override.version}",
                reason = override.reason,
                priorStateJson = prior,
            ),
            context(facts.entityRef, override.docUuid, override.version),
        )
    }

    private fun context(
        entityRef: String,
        docUuid: UUID,
        version: Int,
    ): EventContext {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        return EventContext(
            tenant = tenants.current(),
            entity = EntityRef(entityRef),
            docUuid = DocUuid(docUuid),
            transactionDate = null,
            aggregateKey = docUuid.toString(),
            aggregateVersion = version.toLong(),
            correlation = CorrelationId(correlation),
            causation = CausationId(causation),
        )
    }

    companion object {
        const val ACTION_REQUESTED = "requested"
        const val ACTION_RELEASED = "released"
        const val FIRST_APPROVER_FIELD = "firstApprover"
    }
}
