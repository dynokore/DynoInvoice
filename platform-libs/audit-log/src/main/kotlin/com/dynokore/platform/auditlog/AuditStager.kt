package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext

/**
 * The build-only half of an [AuditWriter] (coordinator rulings INV-Q-4254 A and INV-Q-4277 B): [stage] builds the
 * audit row that `record` would write, and inserts nothing. A SECURITY DEFINER function that writes its own
 * audit entry (for example `purge_audit_entries`, created by `AuditSchema.purgeDdl` in the impl half) takes the
 * staged envelope fields as arguments and checks them inside, so its entry keeps the UUIDv7 id, source, instance
 * and request correlation of every other entry.
 */
interface AuditStager {
    /**
     * @return the row [entry] would be written as under [context], not written.
     * @throws IllegalArgumentException when [AuditEntry.priorStateJson] is not a JSON object.
     */
    fun stage(
        entry: AuditEntry,
        context: EventContext,
    ): StagedAuditEntry
}

/**
 * One audit row as built, not yet written: [envelope] carries the entry id (UUIDv7), the recorded time, source,
 * subject, instance, correlation and causation, the tenant and entity, and the `AuditEntryRecorded` payload
 * ([Envelope.dataJson]); [entry] the actor, action, scope and reason; [priorStateJson] the prior state as the
 * compact JSON object text stored in the row, or `null`.
 */
data class StagedAuditEntry(
    val envelope: Envelope,
    val entry: AuditEntry,
    val priorStateJson: String?,
)
