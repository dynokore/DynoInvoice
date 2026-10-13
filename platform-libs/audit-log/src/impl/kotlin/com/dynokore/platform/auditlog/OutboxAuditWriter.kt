package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import tools.jackson.databind.ObjectMapper

/**
 * [AuditWriter] over outbox-and-queue's [OutboxWriter]: one `AuditEntryRecorded` row in the service's own
 * outbox, on the connection of the caller's open transaction (INV-REQ-1226, SB-05). Any failure of the
 * outbox write surfaces as [AuditWriteFailed].
 *
 * It is also the [AuditStager] of a service with an outbox (coordinator rulings INV-Q-4254 A and INV-Q-4277):
 * [stage] builds the `AuditEntryRecorded` outbox row [record] would write, through [OutboxWriter.stage], and writes
 * nothing, for a SECURITY DEFINER function that inserts the entry with the change it records (config-resolver's
 * `write_installation_setting`). Only an unsequenced platform entry can be staged: a tenantless one under the
 * platform tenant, with no Doc UUID and no aggregate version.
 */
class OutboxAuditWriter(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper = ObjectMapper(),
) : AuditWriter,
    AuditStager {
    override fun record(
        entry: AuditEntry,
        context: EventContext,
    ): Envelope {
        val event = OutboundEvent(EVENT_TYPE, payload(entry))
        return try {
            outbox.append(listOf(event), context).single()
        } catch (failure: Exception) {
            // includes the checked SQLException of a refused insert
            throw AuditWriteFailed(failure)
        }
    }

    /**
     * @throws IllegalArgumentException when [context] is document-scoped or versioned, or the prior state is not a
     *   JSON object.
     * @throws com.dynokore.platform.outboxandqueue.EmissionNotPermitted when the service may not emit the type.
     */
    override fun stage(
        entry: AuditEntry,
        context: EventContext,
    ): StagedAuditEntry {
        val event = OutboundEvent(EVENT_TYPE, payload(entry))
        val row = outbox.stage(listOf(event), context).single()
        return StagedAuditEntry(row.envelope, entry, AuditPayload.priorState(entry, mapper))
    }

    private fun payload(entry: AuditEntry): String = AuditPayload.json(entry, mapper)

    companion object {
        /** The platform event type (API-40), schema owned by `service-runtime`. */
        const val EVENT_TYPE = AuditPayload.EVENT_TYPE
    }
}
