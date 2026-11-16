package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import java.util.UUID

/**
 * One audit entry per send that wrote a request and its SignedLinkRequested (INV-T-20197, INV-REQ-901), through
 * audit-log in the transaction of the send. The actor is the acting user ID and the entry names the request; a role
 * never appears (INV-Q-10647).
 */
class CounterpartyRequestSendAudit(
    private val writer: AuditWriter,
    private val tenants: TenantContext,
) {
    fun sent(
        actor: String,
        requestId: String,
        entity: String,
    ) {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        writer.record(
            AuditEntry(
                actor = actor,
                action = ACTION,
                scope = entity,
                reason = "counterparty request $requestId sent",
            ),
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(entity),
                docUuid = null,
                transactionDate = null,
                aggregateKey = requestId,
                aggregateVersion = null,
                correlation = CorrelationId(correlation),
                causation = CausationId(causation),
            ),
        )
    }

    companion object {
        const val ACTION = "counterparty-request.sent"
    }
}
