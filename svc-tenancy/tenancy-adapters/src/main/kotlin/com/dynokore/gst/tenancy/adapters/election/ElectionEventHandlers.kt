package com.dynokore.gst.tenancy.adapters.election

import com.dynokore.gst.tenancy.application.election.AccessPointRouteStore
import com.dynokore.gst.tenancy.application.election.ApprovedSource
import com.dynokore.gst.tenancy.application.election.RouteUpdate
import com.dynokore.gst.tenancy.application.election.ScopePackStore
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate

/** The event types and topics tenancy-svc consumes through its inbox for the elections (configuration only). */
object ConsumedElectionEvents {
    const val ACCESS_POINT_ROUTE_CHANGED = "com.dynokore.gst.transport.AccessPointRouteChanged.v1"
    const val ACCESS_POINT_ROUTE_CHANGED_TOPIC = "gst.transport.access-point-route-changed.v1"
    const val RULE_PACK_STAGED = "com.dynokore.gst.rules.RulePackStaged.v1"
    const val RULE_PACK_STAGED_TOPIC = "gst.rules.rule-pack-staged.v1"
    const val RULE_PACK_ACTIVATED = "com.dynokore.gst.rules.RulePackActivated.v1"
    const val RULE_PACK_ACTIVATED_TOPIC = "gst.rules.rule-pack-activated.v1"

    /** Event type to topic: exactly the three subscriptions of this service for the elections. */
    val SUBSCRIPTIONS: Map<String, String> =
        mapOf(
            ACCESS_POINT_ROUTE_CHANGED to ACCESS_POINT_ROUTE_CHANGED_TOPIC,
            RULE_PACK_STAGED to RULE_PACK_STAGED_TOPIC,
            RULE_PACK_ACTIVATED to RULE_PACK_ACTIVATED_TOPIC,
        )
}

private fun JsonNode.text(name: String): String =
    this[name]?.takeIf { it.isString }?.asString()?.takeIf { it.isNotEmpty() }
        ?: throw ContractViolation("payload member $name is required")

/** AccessPointRouteChanged (TPL-06): replaces the row of the connection when the version rises, else ignores it. */
class AccessPointRouteChangedHandler(
    private val routes: AccessPointRouteStore,
    private val mapper: ObjectMapper,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        require(event.type == ConsumedElectionEvents.ACCESS_POINT_ROUTE_CHANGED) { "unexpected ${event.type}" }
        val data = mapper.readTree(event.dataJson)
        val version = data["version"]?.takeIf { it.isIntegralNumber }?.asLong() ?: throw ContractViolation("version")
        val refs = data["entityRefs"]?.takeIf { it.isArray } ?: throw ContractViolation("entityRefs is required")
        val update =
            RouteUpdate(
                data.text("connectionRef"),
                refs.values().map { it.asString() },
                data["acknowledgementRoute"]?.takeIf { it.isString }?.asString(),
                version,
            )
        return if (routes.apply(update)) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}

/** RulePackStaged: keeps the approved exchange-rate sources of a staged version of a scope pack only. */
class RulePackStagedHandler(
    private val packs: ScopePackStore,
    private val mapper: ObjectMapper,
    private val now: () -> Instant = Instant::now,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        require(event.type == ConsumedElectionEvents.RULE_PACK_STAGED) { "unexpected ${event.type}" }
        val data = mapper.readTree(event.dataJson)
        if (data.text("packType") != SCOPE) return HandleOutcome.IGNORED
        val sources =
            data["artefact"]
                ?.get(
                    "contents",
                )?.get("exchangeRateSources")
                ?.takeIf { it.isArray }
                ?.values()
                .orEmpty()
                .map {
                    ApprovedSource(it.text("sourceId"), it["bases"].values().map { basis -> basis.asString() }.toSet())
                }
        return if (packs.stage(data.text("packIdentifier"), data.text("version"), sources, now())) {
            HandleOutcome.APPLIED
        } else {
            HandleOutcome.IGNORED
        }
    }

    private companion object {
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
        require(event.type == ConsumedElectionEvents.RULE_PACK_ACTIVATED) { "unexpected ${event.type}" }
        val data = mapper.readTree(event.dataJson)
        val effective = LocalDate.parse(data.text("effectiveFrom"))
        return if (packs.activate(data.text("packIdentifier"), data.text("version"), effective, now())) {
            HandleOutcome.APPLIED
        } else {
            HandleOutcome.IGNORED
        }
    }
}
