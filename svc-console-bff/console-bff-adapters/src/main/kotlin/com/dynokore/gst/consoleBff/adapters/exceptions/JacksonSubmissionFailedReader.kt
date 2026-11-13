package com.dynokore.gst.consoleBff.adapters.exceptions

import com.dynokore.gst.consoleBff.application.exceptions.FailureFacts
import com.dynokore.gst.consoleBff.application.exceptions.SubmissionFailedReader
import com.dynokore.platform.outboxandqueue.ContractViolation

/** [SubmissionFailedReader] over Jackson (submission-failed-payload.schema.json). */
class JacksonSubmissionFailedReader : SubmissionFailedReader {
    override fun read(
        dataJson: String,
        envelopeEntityRef: String?,
        envelopeDocSequence: Long?,
    ): FailureFacts {
        val type = "SubmissionFailed"
        val data = ExceptionPayload.parse(dataJson, type)
        val code = FailurePayload.code(data, type) ?: failureClass(data, type)
        return FailurePayload.facts(data, type, code, envelopeEntityRef, envelopeDocSequence)
    }

    private fun failureClass(
        data: tools.jackson.databind.JsonNode,
        type: String,
    ): String {
        val node = data.get("failureClass")
        if (node == null || !node.isString || node.asString().isBlank()) {
            throw ContractViolation("$type data member failureClass is missing or not a non-blank string")
        }
        return node.asString()
    }
}
