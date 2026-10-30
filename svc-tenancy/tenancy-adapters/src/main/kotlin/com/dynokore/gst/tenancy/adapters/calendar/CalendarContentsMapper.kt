package com.dynokore.gst.tenancy.adapters.calendar

import com.dynokore.platform.calendarandperiods.BusinessCalendar
import com.dynokore.platform.calendarandperiods.CalendarPack
import com.dynokore.platform.calendarandperiods.DueRule
import com.dynokore.platform.calendarandperiods.FilingFrequency
import com.dynokore.platform.calendarandperiods.NonBusinessDayRule
import com.dynokore.platform.calendarandperiods.StandardWindow
import tools.jackson.databind.JsonNode
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate

/** A refusal of the calendar pack decoder: [item] names the first failing item. */
internal class PackRefusal(
    val item: String,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Maps schema-valid calendar pack contents to the [CalendarPack]; reads only the properties the schema names. */
internal object CalendarContentsMapper {
    fun map(
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
}
