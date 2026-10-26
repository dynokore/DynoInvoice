package com.dynokore.gst.evidence.application.auditedread

import com.dynokore.gst.evidence.domain.auditedread.AuditedCall
import com.dynokore.gst.evidence.domain.auditedread.AuditedReadScope
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditedOperations
import com.dynokore.platform.outboxandqueue.EventContext

/**
 * Writes the audit entry of an audited read and hands the answer back only once the entry is written
 * (INV-Q-7207 A; INV-REQ-725, INV-REQ-989). The endpoints call it after validation and authorisation.
 * It catches nothing: `AuditWriteFailed` and the refusal of a blank actor reach the caller.
 */
class AuditedReads(
    private val operations: AuditedOperations,
) {
    fun <T> perform(
        call: AuditedCall,
        actor: String,
        context: EventContext,
        block: () -> T,
    ): T {
        val entry = AuditedReadScope.entryOf(call)
        return operations.read(AuditEntry(actor, entry.action, entry.scope), context, block)
    }
}
