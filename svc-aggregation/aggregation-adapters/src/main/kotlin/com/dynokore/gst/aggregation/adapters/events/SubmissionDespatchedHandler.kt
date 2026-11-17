package com.dynokore.gst.aggregation.adapters.events

import com.dynokore.gst.aggregation.adapters.persistence.JooqHandOffStore
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** The event type and topic of the submission contract channel that names aggregation-svc as consumer. */
object ConsumedSubmissionEvents {
    const val SUBMISSION_DESPATCHED = "com.dynokore.gst.submission.SubmissionDespatched.v1"
    const val SUBMISSION_DESPATCHED_TOPIC = "gst.submission.submission-despatched.v1"
}

/**
 * SubmissionDespatched (INV-REQ-439, event-carried state transfer, idempotent consumer by the stored mark): the
 * optional member `aggregateId` of the data names the aggregate the document was built from. When it names an
 * aggregate of the tenant in the envelope, that aggregate's `transmitted_at` is written once; a redelivery, an absent
 * member, or an id that names no aggregate of the tenant changes nothing and is not a dead letter. A member that is
 * present but not a non-empty string is a [ContractViolation] (the number 12 is never read as "12").
 */
class SubmissionDespatchedHandler(
    private val handOffs: JooqHandOffStore,
    private val mapper: ObjectMapper,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        require(event.type == ConsumedSubmissionEvents.SUBMISSION_DESPATCHED) { "unexpected ${event.type}" }
        val member = mapper.readTree(event.dataJson)["aggregateId"]
        val id = if (member == null || member.isNull) null else aggregateId(member)
        return if (id != null && handOffs.markTransmitted(id)) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }

    /** The id named by a present member; null when the text is no UUID, so it names no aggregate. */
    private fun aggregateId(member: JsonNode): UUID? {
        val text =
            member.takeIf { it.isString }?.asString()?.takeIf { it.isNotEmpty() }
                ?: throw ContractViolation("aggregateId must be a non-empty string")
        return runCatching { UUID.fromString(text) }.getOrNull()
    }
}
