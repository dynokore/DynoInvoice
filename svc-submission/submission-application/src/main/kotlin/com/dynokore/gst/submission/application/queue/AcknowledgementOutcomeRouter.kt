package com.dynokore.gst.submission.application.queue

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome

/** Delegates a whole `AcknowledgementReceived` event by its outcome; an unknown outcome throws (dead letter). */
class AcknowledgementOutcomeRouter(
    private val reader: AcknowledgementOutcomeReader,
    private val accepted: EventHandler,
    private val rejected: EventHandler,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome =
        when (val outcome = reader.outcomeOf(event.dataJson)) {
            AcknowledgementReceivedData.OUTCOME_ACCEPTED -> accepted.handle(event)
            AcknowledgementReceivedData.OUTCOME_REJECTED -> rejected.handle(event)
            else -> throw IllegalArgumentException("AcknowledgementReceived outcome is not known: $outcome")
        }
}
