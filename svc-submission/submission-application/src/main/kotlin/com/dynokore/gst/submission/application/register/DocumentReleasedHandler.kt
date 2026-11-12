package com.dynokore.gst.submission.application.register

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * Clears the hold reason of a provisional entry (INV-REQ-570 behavior 2). A release with a docsequence not above the
 * stored one, or for a document with no provisional row, is ignored.
 */
class DocumentReleasedHandler(
    private val tenants: TenantContext,
    private val reader: DocumentReleasedDataReader,
    private val store: ProvisionalEntryStore,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = reader.read(event.dataJson)
        return if (store.release(tenants.current(), data.docUuid, data.docsequence)) {
            HandleOutcome.APPLIED
        } else {
            HandleOutcome.IGNORED
        }
    }
}
