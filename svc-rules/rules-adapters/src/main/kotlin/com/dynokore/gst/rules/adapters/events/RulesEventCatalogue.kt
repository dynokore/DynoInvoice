package com.dynokore.gst.rules.adapters.events

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue

/**
 * The event types catalogued for rules (platform-contracts event-log/topics.yaml; rules asyncapi.yaml), with the
 * topic of each (the channel address). `RulePackStaged`, `PackCurrencyChanged` and `RulePackActivated` are platform
 * events (INV-T-4976 design question option A; INV-T-23126 for the activated event): platform class, platform tenant,
 * platform entity, not retained. The emission guard of outbox-and-queue refuses any other type
 * (EmissionNotPermitted, INV-AC-1869): there is no second path out of the service.
 */
object RulesEventCatalogue {
    const val SERVICE = "rules-svc"
    const val RULE_PACK_STAGED = "com.dynokore.gst.platform.RulePackStaged.v1"
    const val RULE_PACK_ACTIVATED = "com.dynokore.gst.platform.RulePackActivated.v1"
    const val PACK_CURRENCY_CHANGED = "com.dynokore.gst.platform.PackCurrencyChanged.v1"

    /** Event type to topic, the three events rules publishes. */
    val TOPICS: Map<String, String> =
        mapOf(
            RULE_PACK_STAGED to "gst.platform.rule-pack-staged.v1",
            RULE_PACK_ACTIVATED to "gst.platform.rule-pack-activated.v1",
            PACK_CURRENCY_CHANGED to "gst.platform.pack-currency-changed.v1",
        )

    /** The catalogue entries of the three events rules publishes. */
    fun entries(): List<CatalogueEntry> =
        TOPICS.map { (type, topic) ->
            CatalogueEntry(
                eventType = type,
                topic = topic,
                producers = setOf(SERVICE),
                contentTopic = false,
                retainedWhileResolvable = type == RULE_PACK_STAGED,
            )
        }

    fun catalogue(): EventCatalogue = EventCatalogue(entries())
}
