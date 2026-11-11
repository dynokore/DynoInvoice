package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.application.request.CounterpartyRequests
import com.dynokore.gst.masterdata.application.request.SubmissionAudit
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
 * One audit entry per accepted submission through audit-log, in the submission's transaction. The actor is the
 * principal `link:<linkRef>` of the verified token and the entry names the request; a role never appears
 * (INV-Q-10647, INV-REQ-901: the counterparty has no role and no account).
 */
class LinkSubmissionAudit(
    private val writer: AuditWriter,
    private val tenants: TenantContext,
    private val requests: CounterpartyRequests,
) : SubmissionAudit {
    override fun accepted(
        principal: String,
        requestId: String,
    ) {
        val entity = checkNotNull(requests.find(requestId)) { "no counterparty request $requestId" }.entity
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        writer.record(
            AuditEntry(
                actor = principal,
                action = ACTION,
                scope = entity,
                reason = "counterparty request $requestId answered",
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
        const val ACTION = "counterparty-request.answered"
    }
}
