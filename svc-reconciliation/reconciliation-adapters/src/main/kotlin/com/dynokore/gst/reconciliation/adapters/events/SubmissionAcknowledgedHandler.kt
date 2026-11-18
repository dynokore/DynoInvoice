package com.dynokore.gst.reconciliation.adapters.events

import com.dynokore.gst.reconciliation.application.run.RunReadModelWriter
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The consumer of `gst.submission.submission-acknowledged.v1` for the run read model (INV-REQ-1206): records the IRAS
 * acknowledgement of a version. It runs in the inbox consumer's transaction and opens none. A blank
 * `acknowledgementId`, an unparsable `timestamp` or a missing field is a ContractViolation.
 */
class SubmissionAcknowledgedHandler(
    private val writer: RunReadModelWriter,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = PayloadReading.parse(event.dataJson)
        val applied =
            writer.recordAcknowledgement(
                tenant = event.tenant.toString(),
                docUuid = PayloadReading.uuid(data, "docUuid"),
                version = PayloadReading.positiveInt(data, "version"),
                acknowledgementId = PayloadReading.text(data, "acknowledgementId"),
                acknowledgedAt = timestamp(PayloadReading.text(data, "timestamp")),
            )
        return if (applied) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }

    private fun timestamp(text: String): Instant =
        try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            throw ContractViolation("timestamp is not an instant", e)
        }
}
