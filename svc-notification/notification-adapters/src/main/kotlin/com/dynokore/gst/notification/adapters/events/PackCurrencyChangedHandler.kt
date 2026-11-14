package com.dynokore.gst.notification.adapters.events

import com.dynokore.gst.notification.application.alerting.FanOutPackCurrencyChanged
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import com.dynokore.gst.notification.application.alerting.ContractViolation as PayloadViolation

/**
 * The consumer-side handler of the platform-class PackCurrencyChanged (INV-REQ-1080, option A): an adapter around the
 * framework-free [FanOutPackCurrencyChanged], which raises or resolves one alert per registered tenant. The event
 * carries no tenant, so one under a real tenant is a contract violation. A wrong type or a payload the use case refuses
 * is a contract violation (platform [ContractViolation]) that goes to the dead letter at once. A not-delivered alert
 * (`PackAlertNotDelivered`) propagates, so the event is never marked processed.
 */
class PackCurrencyChangedHandler(
    private val useCase: FanOutPackCurrencyChanged,
    private val mapper: ObjectMapper,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        require(event)
        val payload = parse(event.dataJson)
        val result =
            try {
                useCase.handle(payload)
            } catch (e: PayloadViolation) {
                throw ContractViolation(e.message ?: "payload refused", e)
            }
        return if (result.applied) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }

    private fun require(event: Envelope) {
        if (event.type != EVENT_TYPE) throw ContractViolation("unexpected event type ${event.type}", null)
        if (!event.tenant.isPlatform) {
            throw ContractViolation("a platform event must not carry tenant ${event.tenant.value}", null)
        }
    }

    private fun parse(dataJson: String): Map<String, Any?> =
        try {
            mapper.readValue(dataJson, object : TypeReference<Map<String, Any?>>() {})
        } catch (e: JacksonException) {
            throw ContractViolation("payload is not a JSON object", e)
        }

    companion object {
        const val EVENT_TYPE = "com.dynokore.gst.platform.PackCurrencyChanged.v1"
        const val TOPIC = "gst.platform.pack-currency-changed.v1"
    }
}
