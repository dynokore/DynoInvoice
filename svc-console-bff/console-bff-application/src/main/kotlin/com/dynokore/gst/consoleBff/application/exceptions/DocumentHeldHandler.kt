package com.dynokore.gst.consoleBff.application.exceptions

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * Opens the exception rows of a `DocumentHeld` event (INV-AC-21027). It runs in the consumer's transaction. The raised
 * instant is the envelope `time`. The entityRef is the payload member, else the envelope entity; the envelope's
 * tenant-wide and platform references name no legal entity and count as absent. A malformed event is a
 * `ContractViolation` raised by the reader (dead letter at once, nothing written).
 */
class DocumentHeldHandler(
    private val tenants: TenantContext,
    private val reader: DocumentHeldReader,
    private val record: RecordDocumentHeld,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val envelopeEntityRef = if (event.entity.isTenantWide || event.entity.isPlatform) null else event.entity.value
        val facts = reader.read(event.dataJson, envelopeEntityRef)
        val changed = record.record(tenants.current().value.toString(), facts, event.time)
        return if (changed) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
