package com.dynokore.gst.masterdata.adapters.rate

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate

/** The events and topics masterdata-svc consumes for the approved rate sources (INV-REQ-504, INV-REQ-506). */
object ConsumedRatePackEvents {
    const val RULE_PACK_STAGED = "com.dynokore.gst.rules.RulePackStaged.v1"
    const val RULE_PACK_STAGED_TOPIC = "gst.rules.rule-pack-staged.v1"
    const val RULE_PACK_ACTIVATED = "com.dynokore.gst.rules.RulePackActivated.v1"
    const val RULE_PACK_ACTIVATED_TOPIC = "gst.rules.rule-pack-activated.v1"

    /** Event type to topic: exactly the two subscriptions of this service for the scope pack replica. */
    val SUBSCRIPTIONS: Map<String, String> =
        mapOf(
            RULE_PACK_STAGED to RULE_PACK_STAGED_TOPIC,
            RULE_PACK_ACTIVATED to RULE_PACK_ACTIVATED_TOPIC,
        )
}

/** One approved exchange-rate source of a scope pack and the bases it is approved for. */
data class ApprovedSource(
    val sourceId: String,
    val bases: Set<String>,
)

/** The replica of the scope pack's approved exchange-rate sources, of the bound tenant. */
interface ScopePackStore {
    /** Stores a staged scope pack version; false when the (pack, version) is already held. */
    fun stage(
        packIdentifier: String,
        version: String,
        sources: List<ApprovedSource>,
        recordedAt: Instant,
    ): Boolean

    /** Records an activation; false when it is not above the pack's latest stored activation date. */
    fun activate(
        packIdentifier: String,
        version: String,
        effectiveFrom: LocalDate,
        recordedAt: Instant,
    ): Boolean

    /** The sources of the scope pack versions active on [on], or null when none is (fail closed). */
    fun approvedOn(on: LocalDate): List<ApprovedSource>?
}

private fun JsonNode.text(name: String): String =
    this[name]?.takeIf { it.isString }?.asString()?.takeIf { it.isNotEmpty() }
        ?: throw ContractViolation("payload member $name is required")

/**
 * RulePackStaged: keeps the approved exchange-rate sources of a staged version of a pack whose `packType` is exactly
 * [SCOPE] (equality, nothing normalised: `scope` and `SCOPE ` are other types). A repeat of the same (pack, version)
 * changes nothing.
 */
class RulePackStagedHandler(
    private val packs: ScopePackStore,
    private val mapper: ObjectMapper,
    private val now: () -> Instant = Instant::now,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        require(event.type == ConsumedRatePackEvents.RULE_PACK_STAGED) { "unexpected ${event.type}" }
        val data = mapper.readTree(event.dataJson)
        if (data.text("packType") != SCOPE) return HandleOutcome.IGNORED
        val listed =
            data["artefact"]
                ?.get("contents")
                ?.get("exchangeRateSources")
                ?.takeIf { it.isArray }
                ?.values()
                .orEmpty()
        val sources =
            listed.map { source ->
                val bases = source["bases"]?.takeIf { it.isArray } ?: throw ContractViolation("bases is required")
                ApprovedSource(source.text("sourceId"), bases.values().map { it.asString() }.toSet())
            }
        return if (packs.stage(data.text("packIdentifier"), data.text("version"), sources, now())) {
            HandleOutcome.APPLIED
        } else {
            HandleOutcome.IGNORED
        }
    }

    companion object {
        /** The pack type of the scope pack as the rules contract spells it. */
        const val SCOPE = "SCOPE"
    }
}

/** RulePackActivated: records the version and date; an activation not above the stored date is ignored. */
class RulePackActivatedHandler(
    private val packs: ScopePackStore,
    private val mapper: ObjectMapper,
    private val now: () -> Instant = Instant::now,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        require(event.type == ConsumedRatePackEvents.RULE_PACK_ACTIVATED) { "unexpected ${event.type}" }
        val data = mapper.readTree(event.dataJson)
        val effective =
            runCatching { LocalDate.parse(data.text("effectiveFrom")) }
                .getOrElse { throw ContractViolation("effectiveFrom is not a date") }
        return if (packs.activate(data.text("packIdentifier"), data.text("version"), effective, now())) {
            HandleOutcome.APPLIED
        } else {
            HandleOutcome.IGNORED
        }
    }
}
