package com.dynokore.gst.notification.adapters.events

import com.dynokore.gst.notification.application.alerting.BacklogResultKind
import com.dynokore.gst.notification.application.alerting.HandleBacklogValues
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import org.slf4j.LoggerFactory
import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import com.dynokore.gst.notification.application.alerting.ContractViolation as PayloadViolation

/**
 * The consumer-side handler of BacklogValuesMeasured (INV-REQ-1370, INV-REQ-1222): an adapter around the framework-free
 * [HandleBacklogValues]. It runs inside the consumer's transaction. A wrong type or a payload the use case refuses is a
 * contract violation (platform [ContractViolation]) that goes to the dead letter at once and is never retried.
 */
class BacklogValuesMeasuredHandler(
    private val useCase: HandleBacklogValues,
    private val mapper: ObjectMapper,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        if (event.type != EVENT_TYPE) throw ContractViolation("unexpected event type ${event.type}", null)
        val payload: Map<String, Any?> =
            try {
                mapper.readValue(event.dataJson, object : TypeReference<Map<String, Any?>>() {})
            } catch (e: JacksonException) {
                throw ContractViolation("payload is not a JSON object", e)
            }
        val result =
            try {
                useCase.handle(event.tenant, payload)
            } catch (e: PayloadViolation) {
                throw ContractViolation(e.message ?: "payload refused", e)
            }
        result.results.filter { it.kind == BacklogResultKind.NOT_CONFIGURED }.forEach {
            LOG.info(
                "no backlog threshold configured: entity={} class={}",
                payload["entityRef"],
                it.alertClass,
            )
        }
        return if (result.results.any { it.kind == BacklogResultKind.RAISED }) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.submission.BacklogValuesMeasured.v1"
        const val TOPIC = "gst.submission.backlog-values-measured.v1"
        private val LOG = LoggerFactory.getLogger(BacklogValuesMeasuredHandler::class.java)
    }
}
