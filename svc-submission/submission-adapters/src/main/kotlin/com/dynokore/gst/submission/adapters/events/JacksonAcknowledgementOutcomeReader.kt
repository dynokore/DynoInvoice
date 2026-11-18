package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.queue.AcknowledgementOutcomeReader
import com.dynokore.gst.submission.application.queue.AcknowledgementReceivedData
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

/**
 * [AcknowledgementOutcomeReader] over Jackson. An absent `outcome` reads as ACCEPTED; a value other than ACCEPTED or
 * REJECTED (or a non-string) throws a [ContractViolation] without the value, so the runtime dead-letters the record.
 */
class JacksonAcknowledgementOutcomeReader : AcknowledgementOutcomeReader {
    private val mapper = ObjectMapper()

    override fun outcomeOf(dataJson: String): String {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("AcknowledgementReceived data is not JSON", e)
            }
        if (data == null || !data.isObject) {
            throw ContractViolation("AcknowledgementReceived data is not a JSON object")
        }
        val node = data.get("outcome") ?: return AcknowledgementReceivedData.OUTCOME_ACCEPTED
        val text = if (node.isString) node.asString() else null
        val known = listOf(AcknowledgementReceivedData.OUTCOME_ACCEPTED, AcknowledgementReceivedData.OUTCOME_REJECTED)
        if (text == null || text !in known) {
            throw ContractViolation("AcknowledgementReceived member outcome is not ACCEPTED or REJECTED")
        }
        return text
    }
}
