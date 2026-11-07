package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.application.gapreport.ExportAudit
import com.dynokore.gst.masterdata.application.gapreport.FailureEnvelope
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import java.util.UUID

/** The API-20 envelope text stored on a FAILED record; rendered only by [Problems] (ARC-19). */
class ProblemsFailureEnvelope : FailureEnvelope {
    override fun internalError(): String =
        Problems.render(ClosedErrorCode.INTERNAL_ERROR, UUID.randomUUID().toString()).body
}

/** One audit entry per export through audit-log, in the export's transaction; the actor is the user (INV-Q-10647). */
class AuditLogExportAudit(
    private val writer: AuditWriter,
    private val tenants: TenantContext,
) : ExportAudit {
    override fun exported(
        actor: String,
        entity: String,
        gapReportId: String,
    ) {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        writer.record(
            AuditEntry(actor = actor, action = ACTION, scope = entity, reason = "gap report $gapReportId exported"),
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(entity),
                docUuid = null,
                transactionDate = null,
                aggregateKey = gapReportId,
                aggregateVersion = null,
                correlation = CorrelationId(correlation),
                causation = CausationId(causation),
            ),
        )
    }

    companion object {
        const val ACTION = "gap-report.exported"
    }
}
