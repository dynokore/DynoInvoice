package com.dynokore.gst.tenancy.adapters.calendar

import com.dynokore.platform.calendarandperiods.BusinessCalendar
import com.dynokore.platform.calendarandperiods.CalendarPack
import com.dynokore.platform.calendarandperiods.DueRule
import com.dynokore.platform.calendarandperiods.FilingFrequency
import com.dynokore.platform.calendarandperiods.NonBusinessDayRule
import com.dynokore.platform.calendarandperiods.StandardWindow
import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** The outcome of decoding a calendar pack release: a [Decoded] pack, or a [Refused] naming the first failing item. */
sealed interface CalendarPackDecoding {
    data class Decoded(
        val pack: CalendarPack,
    ) : CalendarPackDecoding

    /** [item] is the first failing item (a JSON location or a rule); never accompanied by a partial pack. */
    data class Refused(
        val item: String,
        val reason: String,
    ) : CalendarPackDecoding
}

/**
 * Turns the `artefact.contents` of a RulePackStaged payload into the [CalendarPack] of calendar-and-periods
 * (INV-T-5055; INV-REQ-83, INV-REQ-87, INV-REQ-88, INV-REQ-1117, INV-REQ-1279). Fail closed: any input that is not a
 * complete, valid calendar pack gives a [CalendarPackDecoding.Refused] and never a partial or defaulted pack.
 *
 * The contents are first validated against `calendar-pack-contents.schema.json` of the rules contract; only the
 * properties that schema names are read, so an unknown property is ignored (API-41, FR-K.4). `entrySources` is checked
 * by the schema and mapped to nothing: [CalendarPack] has no member for it (INV-Q-6556).
 */
class CalendarPackDecoder(
    private val mapper: ObjectMapper,
    private val schema: Schema = contentsSchema(),
) {
    /** Decodes a whole RulePackStaged payload given as JSON text. */
    fun decode(payloadJson: String): CalendarPackDecoding =
        try {
            val (version, contents) = envelope(payloadJson)
            decodeContents(version, contents)
        } catch (e: PackRefusal) {
            CalendarPackDecoding.Refused(e.item, e.message)
        }

    /** The version and the `artefact.contents` of a payload of [PACK_TYPE]; a [PackRefusal] otherwise. */
    private fun envelope(payloadJson: String): Pair<String, JsonNode> {
        val payload =
            try {
                mapper.readTree(payloadJson)
            } catch (e: tools.jackson.core.JacksonException) {
                throw PackRefusal("payload", "the payload is not JSON: ${e.originalMessage}")
            }
        if (!payload.isObject) throw PackRefusal("payload", "the payload is not a JSON object")
        val packType = payload.get("packType")
        if (packType == null || !packType.isString || packType.asString() != PACK_TYPE) {
            throw PackRefusal("packType", "only packType $PACK_TYPE is decoded, found ${packType ?: "none"}")
        }
        val version = payload.get("version")
        if (version == null || !version.isString || version.asString().isBlank()) {
            throw PackRefusal("version", "the payload has no version")
        }
        val contents =
            payload.get("artefact")?.get("contents")
                ?: throw PackRefusal("artefact.contents", "the payload has no artefact.contents")
        return version.asString() to contents
    }

    private fun decodeContents(
        version: String,
        contents: JsonNode,
    ): CalendarPackDecoding {
        val error =
            schema
                .validate(mapper.writeValueAsString(contents), InputFormat.JSON)
                .sortedWith(compareBy({ it.instanceLocation.toString() }, { it.message }))
                .firstOrNull()
        if (error != null) return refused("contents${error.instanceLocation}", error.message)
        return try {
            CalendarPackDecoding.Decoded(map(version, contents))
        } catch (e: PackRefusal) {
            CalendarPackDecoding.Refused(e.item, e.message)
        } catch (e: DateTimeParseException) {
            refused("contents", "not a date: ${e.parsedString}")
        } catch (e: IllegalArgumentException) {
            // The CalendarPack constructor refuses what no schema expresses; its message names the item.
            refused("CalendarPack", e.message ?: "refused by CalendarPack")
        }
    }

    private fun map(
        version: String,
        c: JsonNode,
    ): CalendarPack {
        val windows = windows(c.get("windows"))
        val finalReturnForm = c.get("finalReturnForm").asString()
        val dueRules = dueRules(c.get("dueRules"))
        windows.forEach { (frequency, list) ->
            list.forEachIndexed { index, w ->
                if (w.returnForm !in dueRules) {
                    throw PackRefusal(
                        "contents/windows/${frequency.code}/$index",
                        "return form ${w.returnForm} has no due rule",
                    )
                }
            }
        }
        if (finalReturnForm !in dueRules) {
            throw PackRefusal("contents/finalReturnForm", "return form $finalReturnForm has no due rule")
        }
        return CalendarPack(
            version = version,
            windows = windows,
            finalReturnForm = finalReturnForm,
            dueRules = dueRules,
            calendar = BusinessCalendar(weekend(c.get("weekend")), holidays(c.get("holidays"))),
            pinnedRule = c.get("pinnedRule")?.let { NonBusinessDayRule.valueOf(it.asString()) },
            alertThresholds = alertThresholds(c.get("alertThresholds")),
            safetyMargin = c.get("safetyMargin")?.let(::margin),
        )
    }

    private fun windows(node: JsonNode): Map<FilingFrequency, List<StandardWindow>> =
        node.properties().associate { (code, items) ->
            val list =
                items.values().mapIndexed { index, w ->
                    val at = "contents/windows/$code/$index"
                    val start = LocalDate.parse(w.get("start").asString())
                    val end = LocalDate.parse(w.get("end").asString())
                    if (end.isBefore(
                            start,
                        )
                    ) {
                        throw PackRefusal("$at/end", "the window ends $end, before it starts $start")
                    }
                    StandardWindow(start, end, w.get("returnForm").asString())
                }
            val sorted = list.sortedBy { it.start }
            sorted.zipWithNext().forEach { (a, b) ->
                if (!a.end.isBefore(b.start)) {
                    throw PackRefusal(
                        "contents/windows/$code",
                        "the windows ${a.start}..${a.end} and ${b.start}..${b.end} overlap",
                    )
                }
            }
            FilingFrequency(code) to list
        }

    private fun dueRules(node: JsonNode): Map<String, DueRule> =
        node.properties().associate { (form, r) ->
            form to
                DueRule(r.get("monthsAfterEnd").asInt(), r.get("endOfMonth").asBoolean(), r.get("daysAfterEnd").asInt())
        }

    private fun weekend(node: JsonNode): Set<DayOfWeek> = node.values().map { DayOfWeek.valueOf(it.asString()) }.toSet()

    private fun holidays(node: JsonNode?): Set<LocalDate> =
        node
            ?.values()
            ?.map {
                LocalDate.parse(it.asString())
            }?.toSet()
            .orEmpty()

    private fun alertThresholds(node: JsonNode?): Map<FilingFrequency, List<Int>> =
        node
            ?.properties()
            ?.associate { (code, days) ->
                FilingFrequency(code) to days.values().map { it.asInt() }
            }.orEmpty()

    /** DAYS by [Duration.ofDays], HOURS by [Duration.ofHours] (INV-Q-6554). */
    private fun margin(node: JsonNode): Duration {
        val value = node.get("value").asLong()
        return when (val unit = node.get("unit").asString()) {
            "DAYS" -> Duration.ofDays(value)
            "HOURS" -> Duration.ofHours(value)
            else -> throw PackRefusal("contents/safetyMargin/unit", "unit $unit is neither DAYS nor HOURS")
        }
    }

    private fun refused(
        item: String,
        reason: String,
    ) = CalendarPackDecoding.Refused(item, reason)

    private class PackRefusal(
        val item: String,
        override val message: String,
    ) : RuntimeException(message)

    companion object {
        /** The only pack type this decoder applies to, by equality. */
        const val PACK_TYPE = "calendar"
        private const val SCHEMA_RESOURCE = "gst/contracts/rules/schemas/calendar-pack-contents.schema.json"

        /** The schema of the pinned rules contract, staged on the classpath by the build. */
        fun contentsSchema(): Schema =
            SchemaRegistry
                .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(
                    checkNotNull(CalendarPackDecoder::class.java.classLoader.getResourceAsStream(SCHEMA_RESOURCE)) {
                        "$SCHEMA_RESOURCE is not on the classpath"
                    }.use { it.readBytes().toString(Charsets.UTF_8) },
                )
    }
}
