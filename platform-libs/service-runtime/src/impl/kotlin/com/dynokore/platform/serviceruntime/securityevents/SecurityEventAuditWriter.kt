package com.dynokore.platform.serviceruntime.securityevents

import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditStager
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.auditlog.StagedAuditEntry
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.tenancykernel.TransactionRunner

/**
 * Decorates an [AuditWriter]: after the delegate recorded the entry, an action that is a key of
 * `gst.security-events.mapping` (string equality, INV-Q-11054) registers its emission with
 * [TransactionRunner.afterCommit] (INV-Q-11056), so a rolled-back entry emits nothing. A staged entry
 * ([AuditStager.stage]) is passed through unchanged.
 */
open class SecurityEventAuditWriter(
    val delegate: AuditWriter,
    private val properties: () -> SecurityEventProperties,
    private val emitter: () -> SecurityEventEmitter,
    private val transactions: () -> TransactionRunner,
) : AuditWriter {
    override fun record(
        entry: AuditEntry,
        context: EventContext,
    ): Envelope {
        val envelope = delegate.record(entry, context)
        val securityClass = properties().classOf(entry.action)
        if (securityClass != null) {
            val actor = entry.actor
            val tenant = context.tenant.toString()
            transactions().afterCommit { emitter().emit(securityClass, actor, tenant) }
        }
        return envelope
    }
}

/** [SecurityEventAuditWriter] over a delegate that is also an [AuditStager]; it stays one. */
class SecurityEventAuditStager(
    delegate: AuditWriter,
    properties: () -> SecurityEventProperties,
    emitter: () -> SecurityEventEmitter,
    transactions: () -> TransactionRunner,
) : SecurityEventAuditWriter(delegate, properties, emitter, transactions),
    AuditStager {
    private val stager: AuditStager = requireNotNull(delegate as? AuditStager) { "the delegate must be an AuditStager" }

    override fun stage(
        entry: AuditEntry,
        context: EventContext,
    ): StagedAuditEntry = stager.stage(entry, context)
}
