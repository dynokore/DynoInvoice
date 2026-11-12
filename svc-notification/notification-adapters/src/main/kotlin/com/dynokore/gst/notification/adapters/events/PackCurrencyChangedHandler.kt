package com.dynokore.gst.notification.adapters.events

import com.dynokore.gst.notification.application.alerting.HandlePackCurrencyChanged
import com.dynokore.gst.notification.application.alerting.HandlePackCurrencyChangedResult
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import com.dynokore.gst.notification.application.alerting.ContractViolation as PayloadViolation

/**
 * The consumer-side handler of PackCurrencyChanged (INV-REQ-1080, INV-Q-20864 A): an adapter around the framework-free
 * [HandlePackCurrencyChanged]. It runs inside the consumer's transaction. A wrong type or a payload the use case
 * refuses is a contract violation (platform [ContractViolation]) that goes to the dead letter at once. A not-delivered
 * alert (`PackAlertNotDelivered`) propagates, so the event is never marked processed.
 */
class PackCurrencyChangedHandler(
    private val useCase: HandlePackCurrencyChanged,
    private val mapper: ObjectMapper,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        if (event.type != EVENT_TYPE) throw ContractViolation("unexpected event type ${event.type}", null)
        val payload = parse(event.dataJson)
        val result =
            try {
                useCase.handle(event.tenant, payload)
            } catch (e: PayloadViolation) {
                throw ContractViolation(e.message ?: "payload refused", e)
            }
        return when (result) {
            HandlePackCurrencyChangedResult.Raised, HandlePackCurrencyChangedResult.Resolved -> HandleOutcome.APPLIED
            else -> HandleOutcome.IGNORED
        }
    }

    private fun parse(dataJson: String): Map<String, Any?> =
        try {
            mapper.readValue(dataJson, object : TypeReference<Map<String, Any?>>() {})
        } catch (e: JacksonException) {
            throw ContractViolation("payload is not a JSON object", e)
        }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.rules.PackCurrencyChanged.v1"
        const val TOPIC = "gst.rules.pack-currency-changed.v1"
    }
}
