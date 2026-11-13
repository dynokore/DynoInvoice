package com.dynokore.gst.consoleBff.application.exceptions

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * Resolves the OPEN exception rows named by a `DocumentReleased` event (INV-AC-21027). Each stored row keeps its own
 * entityRef, so the handler needs none. A malformed event is a `ContractViolation` raised by the reader.
 */
class DocumentReleasedHandler(
    private val tenants: TenantContext,
    private val reader: DocumentReleasedReader,
    private val record: RecordDocumentReleased,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val facts = reader.read(event.dataJson)
        val changed = record.record(tenants.current().value.toString(), facts)
        return if (changed) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
