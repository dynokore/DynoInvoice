package com.dynokore.gst.tenancy.adapters.calendar

import com.dynokore.gst.tenancy.application.period.CalendarPackReplica
import com.dynokore.gst.tenancy.application.period.PackActivationRecompute
import com.dynokore.gst.tenancy.application.period.StagedCalendarPack
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** The inbox consumers of the calendar pack replica: one name per topic, apart from the scope pack consumers. */
object ConsumedCalendarEvents {
    const val STAGED_CONSUMER = "tenancy-svc-calendar-pack-staged"
    const val ACTIVATED_CONSUMER = "tenancy-svc-calendar-pack-activated"
}

private fun JsonNode.text(name: String): String =
    this[name]?.takeIf { it.isString }?.asString()?.takeIf { it.isNotEmpty() }
        ?: throw ContractViolation("payload member $name is required")

/**
 * RulePackStaged for the calendar packs (INV-Q-9725 A). A payload whose `packType` is not equal to `calendar` is
 * ignored (case matters: `Calendar` is another type). A calendar payload the decoder of INV-T-5055 refuses is a
 * contract violation and stores nothing (fail closed).
 */
class CalendarPackStagedHandler(
    private val replica: CalendarPackReplica,
    private val mapper: ObjectMapper,
    private val decoder: CalendarPackDecoder,
    private val now: () -> Instant = Instant::now,
    private val effects: PackActivationRecompute? = null,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        require(event.type == STAGED) { "unexpected ${event.type}" }
        val data = mapper.readTree(event.dataJson)
        if (data["packType"]?.takeIf { it.isString }?.asString() != CalendarPackDecoder.PACK_TYPE) {
            return HandleOutcome.IGNORED
        }
        val refused = decoder.decode(event.dataJson) as? CalendarPackDecoding.Refused
        if (refused != null) throw ContractViolation("calendar pack refused at ${refused.item}: ${refused.reason}")
        val pack =
            StagedCalendarPack(
                data.text("packIdentifier"),
                data.text("version"),
                data.text("minEngineVersion"),
                event.dataJson,
            )
        val stored = replica.stage(pack, now())
        // an activation delivered before this staging recomputed nothing: run the same idempotent check (INV-Q-16814)
        effects?.onStaged(pack.packIdentifier, pack.version)
        return if (stored) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }

    companion object {
        const val STAGED = "com.dynokore.gst.rules.RulePackStaged.v1"
    }
}

/**
 * RulePackActivated: records the version and date of every pack identifier (the topics are not ordered against each
 * other); the source joins them to the staged calendar versions, so another pack type's activation never resolves.
 * When the activated version is a staged calendar pack in force on its date, the unfiled periods are recomputed from
 * it in the same transaction and PeriodDueDateChanged is published for each due date that moves (INV-REQ-88). The
 * staged consumer runs the same check, so the two orders give the same result (INV-Q-16814).
 */
class CalendarPackActivatedHandler(
    private val replica: CalendarPackReplica,
    private val mapper: ObjectMapper,
    private val now: () -> Instant = Instant::now,
    private val effects: PackActivationRecompute? = null,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        require(event.type == ACTIVATED) { "unexpected ${event.type}" }
        val data = mapper.readTree(event.dataJson)
        val effective =
            try {
                LocalDate.parse(data.text("effectiveFrom"))
            } catch (e: DateTimeParseException) {
                throw ContractViolation("effectiveFrom is not a date: ${e.message}")
            }
        val identifier = data.text("packIdentifier")
        val version = data.text("version")
        val stored = replica.activate(identifier, version, effective, now())
        effects?.onActivated(identifier, version, effective)
        return if (stored) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }

    companion object {
        const val ACTIVATED = "com.dynokore.gst.rules.RulePackActivated.v1"
    }
}
