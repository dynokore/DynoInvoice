package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext

/**
 * Writes an audit entry as `AuditEntryRecorded` through the producing service's outbox, in the
 * caller's open transaction (INV-REQ-1226, ARC-05): the entry commits with the change it records
 * or vanishes with it. `evidence-svc` stores it later (INV-ADR-282). Storage is not this module's.
 */
interface AuditWriter {
    /**
     * @return the stamped envelope of the entry.
     * @throws AuditWriteFailed when the entry cannot be written; the caller's transaction must not commit.
     * @throws IllegalArgumentException when [AuditEntry.priorStateJson] is not a JSON object.
     */
    fun record(
        entry: AuditEntry,
        context: EventContext,
    ): Envelope
}

/** The audit entry could not be written (INV-AC-2593): the operation has not happened and returns no result. */
class AuditWriteFailed(
    cause: Throwable,
) : RuntimeException("audit entry could not be written", cause)
