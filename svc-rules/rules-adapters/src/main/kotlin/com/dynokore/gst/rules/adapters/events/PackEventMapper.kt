package com.dynokore.gst.rules.adapters.events

import com.dynokore.gst.rules.application.PackEvent
import com.dynokore.platform.outboxandqueue.OutboundEvent
import tools.jackson.databind.json.JsonMapper

/**
 * Maps a [PackEvent] to the contract's event type and payload (TPL-05). The payload is the `data` of the rules
 * asyncapi.yaml message, field for field; a test per channel validates it against the AsyncAPI schema.
 */
class PackEventMapper(
    private val mapper: JsonMapper = JsonMapper.builder().build(),
) {
    fun map(event: PackEvent): OutboundEvent =
        when (event) {
            is PackEvent.RulePackStaged ->
                OutboundEvent(
                    RulesEventCatalogue.RULE_PACK_STAGED,
                    mapper.writeValueAsString(
                        linkedMapOf(
                            "packIdentifier" to event.packIdentifier,
                            "packType" to event.packType,
                            "version" to event.version,
                            "effectiveFrom" to event.effectiveFrom.toString(),
                            "minEngineVersion" to event.minEngineVersion,
                            "artefact" to
                                linkedMapOf(
                                    "manifest" to event.manifest,
                                    "checksums" to event.checksums,
                                    "signature" to event.signature,
                                ),
                        ),
                    ),
                )

            is PackEvent.RulePackActivated ->
                OutboundEvent(
                    RulesEventCatalogue.RULE_PACK_ACTIVATED,
                    mapper.writeValueAsString(
                        linkedMapOf(
                            "packIdentifier" to event.packIdentifier,
                            "version" to event.version,
                            "effectiveFrom" to event.effectiveFrom.toString(),
                        ),
                    ),
                )

            is PackEvent.PackCurrencyChanged ->
                OutboundEvent(
                    RulesEventCatalogue.PACK_CURRENCY_CHANGED,
                    mapper.writeValueAsString(
                        buildMap<String, Any> {
                            put("depCode", event.depCode)
                            put("status", if (event.raised) "RAISED" else "CLEARED")
                            event.packIdentifier?.let { put("packIdentifier", it) }
                            event.packVersion?.let { put("packVersion", it) }
                            event.daysToEffectiveDate?.let { put("daysToEffectiveDate", it) }
                            event.daysSinceLastReached?.let { put("daysSinceLastReached", it) }
                        },
                    ),
                )
        }
}
