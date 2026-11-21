package com.dynokore.gst.submission.application.readiness

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome

/**
 * Stores the unreviewed activation of an arming event under the tenant of the event (INV-REQ-952). A repeated
 * delivery is IGNORED; a malformed payload is a [ContractViolation] and stores nothing.
 */
class UnreviewedActivationRecordedHandler(
    private val reader: UnreviewedActivationDataReader,
    private val store: UnreviewedActivationStore,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = reader.read(event.dataJson)
        return if (store.record(data.tenant, data.activation)) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
