package com.dynokore.gst.controlPlane.adapters.seed

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Gate (entry sources, fails closed; INV-AC-1727, INV-Q-6556): `entrySources` holds exactly one row for every entry
 * of contents.json, no row names another entry, and the class and reference of a row equal, by string equality, the
 * class and citation of the entry in sources.tsv.
 */
class CalendarSeedEntrySourcesTest {
    companion object {
        fun violations(
            contentsText: String,
            tsv: String,
        ): List<String> {
            val problems = ArrayList<String>()
            val root = CalendarSeedFiles.mapper.readTree(contentsText)
            val rows = CalendarSeedFiles.rows(tsv, problems).associateBy { it.path }
            val entries = LinkedHashMap<String, MutableList<String>>()
            for (path in CalendarSeedFiles.leaves(root).keys) {
                entries.getOrPut(CalendarSeedFiles.entryOf(root, path)) { ArrayList() } += path
            }
            val authored =
                entries.mapValues { (key, paths) ->
                    val own = paths.mapNotNull { rows[it] }
                    val shapes = own.map { listOf(it.kind, it.sourceClass, it.citation, it.retrievedOn) }.toSet()
                    if (shapes.size > 1) problems += "$key: its leaves differ in kind, class, citation or retrieved-on in sources.tsv"
                    own.firstOrNull()
                }
            val counts = LinkedHashMap<String, Int>()
            for (row in root.path("entrySources")) {
                val key = CalendarSeedFiles.entryOfRow(row)
                if (key == null) {
                    problems += "invalid row: the entrySources row $row names no entry"
                    continue
                }
                counts.merge(key, 1, Int::plus)
                if (key !in entries) {
                    problems += "$key: the entrySources row names an entry that contents.json does not hold"
                    continue
                }
                val source = authored[key] ?: continue
                if (row.path("sourceClass").asString() != source.sourceClass) {
                    problems += "$key: the row class '${row.path("sourceClass").asString()}' differs from sources.tsv '${source.sourceClass}'"
                }
                if (row.path("sourceReference").asString() != source.citation) {
                    problems += "$key: the row reference differs from the citation in sources.tsv"
                }
            }
            for (key in entries.keys) {
                when (counts[key] ?: 0) {
                    0 -> problems += "$key: the entry has no row in entrySources"
                    1 -> {}
                    else -> problems += "$key: the entry has ${counts[key]} rows in entrySources"
                }
            }
            return problems
        }

        private fun rows(root: ObjectNode) = root.get("entrySources") as ArrayNode

        fun mutated(edit: (ArrayNode) -> Unit): String {
            val root = CalendarSeedFiles.mapper.readTree(CalendarSeedFiles.contents) as ObjectNode
            edit(rows(root))
            return root.toString()
        }

        fun rowOf(
            rows: ArrayNode,
            section: String,
            date: String? = null,
        ) = rows.indexOfFirst { it.path("section").asString() == section && (date == null || it.path("date").asString() == date) }
    }

    private fun failsNaming(
        text: String,
        entry: String,
    ) = violations(text, CalendarSeedFiles.sources).any { it.startsWith("$entry:") } shouldBe true

    @Test
    fun `INV-AC-1727 the shipped pack has exactly one entrySources row per entry`() {
        violations(CalendarSeedFiles.contents, CalendarSeedFiles.sources).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-1727 probe a deleted entrySources row fails naming the entry`() {
        failsNaming(mutated { it.remove(rowOf(it, "holidays", "2026-05-01")) }, "holidays/2026-05-01")
    }

    @Test
    fun `INV-AC-1727 probe a duplicated entrySources row fails naming the entry`() {
        failsNaming(mutated { it.add(it.get(rowOf(it, "safetyMargin")).deepCopy() as ObjectNode) }, "safetyMargin")
    }

    @Test
    fun `INV-AC-1727 probe a changed class fails naming the entry`() {
        failsNaming(mutated { (it.get(rowOf(it, "finalReturnForm")) as ObjectNode).put("sourceClass", "PRIMARY") }, "finalReturnForm")
    }

    @Test
    fun `INV-AC-1727 probe the citation of one leaf of a window only fails naming the entry`() {
        val tsv = CalendarSeedSourcesTest.withRow("/windows/MONTHLY/3/end") { it.toMutableList().also { c -> c[4] = "INV-AC-113" } }
        violations(CalendarSeedFiles.contents, tsv).any { it.startsWith("windows/MONTHLY/2025-04-01:") } shouldBe true
    }

    @Test
    fun `INV-AC-1727 probe a holiday row moved by one day fails naming the entry`() {
        val text = mutated { (it.get(rowOf(it, "holidays", "2026-05-01")) as ObjectNode).put("date", "2026-05-02") }
        failsNaming(text, "holidays/2026-05-02")
        failsNaming(text, "holidays/2026-05-01")
    }

    @Test
    fun `INV-AC-1727 probe a row for the section pinnedRule fails naming the entry`() {
        val text =
            mutated {
                it.add(
                    CalendarSeedFiles.mapper
                        .createObjectNode()
                        .put("section", "pinnedRule")
                        .put("sourceClass", "INFERRED")
                        .put("sourceReference", "INV-REQ-87"),
                )
            }
        failsNaming(text, "pinnedRule")
    }

    @Test
    fun `INV-AC-1727 look-alike rows in the other order of the array pass`() {
        val reversed = mutated { rows -> rows.items().reversed().let { r -> rows.removeAll(); r.forEach { rows.add(it) } } }
        violations(reversed, CalendarSeedFiles.sources).shouldBeEmpty()
    }
}
