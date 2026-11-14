package com.dynokore.gst.rules.adapters.packs

import com.dynokore.gst.rules.application.PackContentsCheck
import com.dynokore.gst.rules.domain.resolution.PackTypes
import tools.jackson.databind.JsonNode

/**
 * The checks of pack contents that JSON Schema cannot express, run at the import after the contents load (INV-T-6567):
 * the calendar pack's defaults and one source row per entry (INV-Q-6554, INV-Q-6556; INV-REQ-1117, INV-REQ-1279,
 * INV-REQ-774, INV-AC-1727), and the source class of each required rendition wording of a scenario pack (INV-Q-6556).
 * A pack type is matched by exact equality with the registry's value, so no look-alike casing is checked as another
 * type. The answer names the first failing item; the pack is never defaulted or partially accepted.
 */
class PackContentsChecks : PackContentsCheck {
    override fun refusal(
        packType: String,
        contents: ByteArray,
    ): String? =
        when (packType) {
            PackTypes.CALENDAR.value -> guarded(packType, contents, CalendarPackChecks::refusal)
            PackTypes.SCENARIO.value -> guarded(packType, contents, ScenarioRenditionChecks::refusal)
            else -> null
        }

    /** A shape the schema would have refused is refused here too, naming the member (fail closed). */
    private fun guarded(
        packType: String,
        contents: ByteArray,
        check: (ContentsNode) -> String?,
    ): String? =
        try {
            check(ContentsNode.root(packType, contents))
        } catch (e: PackContentsFailure) {
            e.message
        }
}

/** The calendar checks, in the order of the task text; the first failing item is named. */
internal object CalendarPackChecks {
    private val SECTIONS =
        setOf(
            "windows",
            "finalReturnForm",
            "dueRules",
            "weekend",
            "holidays",
            "pinnedRule",
            "alertThresholds",
            "safetyMargin",
        )

    fun refusal(root: ContentsNode): String? {
        val windows = root.member("windows") ?: return "windows is required"
        val thresholds = root.member("alertThresholds")
        if (thresholds != null && !thresholds.isObject) return "alertThresholds must be an object"
        return frequencyRefusal(windows, thresholds)
            ?: descendingRefusal(thresholds)
            ?: marginRefusal(root)
            ?: sourcesRefusal(root, entriesOf(root, windows, thresholds))
    }

    private fun keys(node: JsonNode?): List<String> =
        node
            ?.takeIf { it.isObject }
            ?.propertyNames()
            ?.toList()
            .orEmpty()

    private fun frequencyRefusal(
        windows: JsonNode,
        thresholds: JsonNode?,
    ): String? {
        val frequencies = keys(windows)
        val thresholdKeys = keys(thresholds)
        return frequencies.firstOrNull { it !in thresholdKeys }?.let {
            "windows.$it has no entry in alertThresholds"
        } ?: thresholdKeys.firstOrNull { it !in frequencies }?.let {
            "alertThresholds.$it is not a key of windows"
        }
    }

    private fun descendingRefusal(thresholds: JsonNode?): String? =
        keys(thresholds).firstNotNullOfOrNull { key ->
            val list = thresholds?.get(key)?.takeIf { it.isArray }?.toList()
            when {
                list == null || list.any { !it.isIntegralNumber } -> {
                    "alertThresholds.$key must be a list of integers"
                }

                list.map { it.asLong() }.zipWithNext().any { (a, b) -> b >= a } -> {
                    "alertThresholds.$key is not strictly descending"
                }

                else -> {
                    null
                }
            }
        }

    private fun marginRefusal(root: ContentsNode): String? =
        if (root.has("safetyMargin")) null else "safetyMargin is absent"

    /** The identity of an entry as an `entrySources` row names it. */
    private data class Key(
        val section: String,
        val frequency: String? = null,
        val returnForm: String? = null,
        val date: String? = null,
    ) {
        override fun toString(): String =
            listOfNotNull(
                section,
                frequency?.let {
                    "frequency=$it"
                },
                returnForm?.let { "returnForm=$it" },
                date?.let { "date=$it" },
            ).joinToString(" ")
    }

    private fun entriesOf(
        root: ContentsNode,
        windows: JsonNode,
        thresholds: JsonNode?,
    ): List<Key> {
        val entries = mutableListOf<Key>()
        for (frequency in keys(windows)) {
            val list = windows.get(frequency)
            if (list != null && list.isArray) {
                list.forEach { w ->
                    entries += Key("windows", frequency = frequency, date = w.get("start")?.asString())
                }
            }
        }
        entries += Key("finalReturnForm")
        keys(root.member("dueRules")).forEach { entries += Key("dueRules", returnForm = it) }
        entries += Key("weekend")
        root.member("holidays")?.takeIf { it.isArray }?.forEach { entries += Key("holidays", date = it.asString()) }
        if (root.has("pinnedRule")) entries += Key("pinnedRule")
        keys(thresholds).forEach { entries += Key("alertThresholds", frequency = it) }
        entries += Key("safetyMargin")
        return entries
    }

    private fun sourcesRefusal(
        root: ContentsNode,
        entries: List<Key>,
    ): String? {
        val rows =
            root
                .member("entrySources")
                ?.takeIf { it.isArray }
                ?.toList()
                .orEmpty()
        val keyed = rows.mapIndexed { index, row -> index to keyOf(row) }
        val held = entries.toSet()
        return classRefusal(rows)
            ?: entries.firstNotNullOfOrNull { entry -> countRefusal(entry, keyed.count { it.second == entry }) }
            ?: keyed.firstOrNull { it.second !in held }?.let { (index, key) ->
                "entrySources[$index] names an entry the contents do not hold: ${key ?: "unreadable row"}"
            }
    }

    private fun countRefusal(
        entry: Key,
        count: Int,
    ): String? =
        when (count) {
            0 -> "entrySources has no row for entry $entry"
            1 -> null
            else -> "entrySources has $count rows for entry $entry"
        }

    /** A row without a class or citation is refused; an unclassified entry is never read as PRIMARY (INV-AC-1727). */
    private fun classRefusal(rows: List<JsonNode>): String? =
        rows.withIndex().firstNotNullOfOrNull { (index, row) ->
            val cls = row.get("sourceClass")
            val ref = row.get("sourceReference")
            when {
                cls == null || !cls.isString || cls.asString() !in SOURCE_CLASSES -> {
                    "entrySources[$index].sourceClass is required and must be one of ${SOURCE_CLASSES.sorted()}"
                }

                ref == null || !ref.isString || ref.asString().isEmpty() -> {
                    "entrySources[$index].sourceReference is required"
                }

                else -> {
                    null
                }
            }
        }

    private fun keyOf(row: JsonNode): Key? {
        fun text(name: String) = row.get(name)?.takeIf { it.isString }?.asString()
        val section = text("section")?.takeIf { it in SECTIONS } ?: return null
        return when (section) {
            "windows" -> Key(section, frequency = text("frequency"), date = text("date"))
            "dueRules" -> Key(section, returnForm = text("returnForm"))
            "holidays" -> Key(section, date = text("date"))
            "alertThresholds" -> Key(section, frequency = text("frequency"))
            else -> Key(section)
        }
    }
}

/** The rendition wording of a scenario pack: an item without a source class refuses the pack (INV-Q-6556). */
internal object ScenarioRenditionChecks {
    fun refusal(root: ContentsNode): String? =
        root
            .optionalArray("renditionWording", 0, Int.MAX_VALUE)
            .orEmpty()
            .withIndex()
            .firstNotNullOfOrNull { (i, entry) -> entryRefusal(i, entry) }

    private fun entryRefusal(
        i: Int,
        entry: ContentsNode,
    ): String? =
        entry
            .optionalArray("requiredWording", 0, Int.MAX_VALUE)
            .orEmpty()
            .withIndex()
            .firstNotNullOfOrNull { (j, item) ->
                if (item.has("sourceClass")) {
                    item.text("sourceClass", oneOf = SOURCE_CLASSES)
                    null
                } else {
                    "renditionWording[$i].requiredWording[$j].sourceClass is required"
                }
            }
}
