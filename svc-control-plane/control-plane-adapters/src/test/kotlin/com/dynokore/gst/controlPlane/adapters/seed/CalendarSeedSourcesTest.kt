package com.dynokore.gst.controlPlane.adapters.seed

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Gate (source table, fails closed; INV-AC-1727): every leaf of contents.json has a row in sources.tsv, every row names
 * a leaf, and each row carries a kind, class, citation and retrieval date of the right form.
 */
class CalendarSeedSourcesTest {
    companion object {
        private val KINDS = setOf("REQUIREMENT", "LAW")
        private val CLASSES = setOf("PRIMARY", "SECONDARY", "INFERRED")
        private val KEEL_KEY = Regex("INV-(AC|REQ)-[0-9]+")
        private val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

        fun violations(
            contentsText: String,
            tsv: String,
        ): List<String> {
            val problems = ArrayList<String>()
            val root = CalendarSeedFiles.mapper.readTree(contentsText)
            val leaves = CalendarSeedFiles.leaves(root)
            val rows = CalendarSeedFiles.rows(tsv, problems)
            for (name in listOf("pinnedRule", "transition")) {
                if (root.has(name)) problems += "/$name: contents.json holds $name, which stays absent"
            }
            problems += leafProblems(leaves, rows)
            rows.forEach { problems += rowProblems(it, it.path in leaves) }
            return problems
        }

        private fun leafProblems(
            leaves: Map<String, String>,
            rows: List<SourceRow>,
        ): List<String> {
            val byPath = rows.groupBy { it.path }
            return leaves.flatMap { (path, value) ->
                val own = byPath[path].orEmpty()
                listOfNotNull(
                    "$path: the leaf has no row in sources.tsv".takeIf { own.isEmpty() },
                    "$path: ${own.size} rows in sources.tsv".takeIf { own.size > 1 },
                    own
                        .firstOrNull()
                        ?.takeIf {
                            it.value != value
                        }?.let { "$path: the row value ${it.value} is not the leaf $value" },
                )
            }
        }

        private fun rowProblems(
            row: SourceRow,
            known: Boolean,
        ): List<String> {
            val p = row.path
            val requirement = row.kind == "REQUIREMENT"
            return listOfNotNull(
                "$p: the row names a path that contents.json does not hold".takeIf { !known },
                "$p: the citation is empty".takeIf { row.citation.isBlank() },
                "$p: source kind '${row.kind}' is not REQUIREMENT or LAW".takeIf { row.kind !in KINDS },
                "$p: source class '${row.sourceClass}' is not PRIMARY, SECONDARY or INFERRED".takeIf {
                    row.sourceClass !in
                        CLASSES
                },
                "$p: the REQUIREMENT citation '${row.citation}' is not a Keel key INV-AC-n or INV-REQ-n"
                    .takeIf { requirement && !KEEL_KEY.matches(row.citation) },
                "$p: the LAW row has no retrieval date".takeIf { row.kind == "LAW" && !DATE.matches(row.retrievedOn) },
            )
        }

        fun withContents(edit: (ObjectNode) -> Unit): String {
            val root = CalendarSeedFiles.mapper.readTree(CalendarSeedFiles.contents) as ObjectNode
            edit(root)
            return root.toString()
        }

        fun withRow(
            path: String,
            edit: (List<String>) -> List<String>?,
        ): String =
            CalendarSeedFiles.sources
                .split("\n")
                .mapNotNull { line ->
                    val c = line.split("\t")
                    if (c[0] == path) edit(c)?.joinToString("\t") else line
                }.joinToString("\n")
    }

    private fun named(
        problems: List<String>,
        path: String,
    ) = problems.filter { it.startsWith("$path:") }.isNotEmpty() shouldBe true

    @Test
    fun `INV-AC-1727 the shipped sources table covers every leaf and passes every check`() {
        violations(CalendarSeedFiles.contents, CalendarSeedFiles.sources).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-1727 probe a holiday added without a row fails naming the path`() {
        val text = withContents { (it.get("holidays") as ArrayNode).add("2027-06-15") }
        val found = violations(text, CalendarSeedFiles.sources)
        named(found, "/holidays/38")
    }

    @Test
    fun `INV-AC-1727 probe a deleted row fails naming the path`() {
        named(violations(CalendarSeedFiles.contents, withRow("/safetyMargin/unit") { null }), "/safetyMargin/unit")
    }

    @Test
    fun `INV-AC-1727 probe an empty citation fails naming the path`() {
        val tsv = withRow("/finalReturnForm") { it.toMutableList().also { c -> c[4] = "" } }
        named(violations(CalendarSeedFiles.contents, tsv), "/finalReturnForm")
    }

    @Test
    fun `INV-AC-1727 probe a source kind OTHER fails naming the path`() {
        val tsv = withRow("/finalReturnForm") { it.toMutableList().also { c -> c[2] = "OTHER" } }
        named(violations(CalendarSeedFiles.contents, tsv), "/finalReturnForm")
    }

    @Test
    fun `INV-AC-1727 probe a citation AC-113 that is not a Keel key fails naming the path`() {
        val tsv = withRow("/windows/QUARTERLY/0/start") { it.toMutableList().also { c -> c[4] = "AC-113" } }
        named(violations(CalendarSeedFiles.contents, tsv), "/windows/QUARTERLY/0/start")
    }

    @Test
    fun `INV-AC-1727 probe a lower case class fails naming the path`() {
        val tsv = withRow("/safetyMargin/value") { it.toMutableList().also { c -> c[3] = "primary" } }
        named(violations(CalendarSeedFiles.contents, tsv), "/safetyMargin/value")
    }

    @Test
    fun `INV-AC-1727 probe a LAW row without a retrieval date fails naming the path`() {
        val tsv = withRow("/weekend/0") { it.toMutableList().also { c -> c[5] = "" } }
        named(violations(CalendarSeedFiles.contents, tsv), "/weekend/0")
    }

    @Test
    fun `INV-AC-1727 probe a row for a path contents does not hold fails naming the path`() {
        val extra = "/holidays/99\t\"2027-06-15\"\tLAW\tPRIMARY\tMOM\t2026-10-26"
        named(
            violations(CalendarSeedFiles.contents, CalendarSeedFiles.sources.trimEnd('\n') + "\n" + extra + "\n"),
            "/holidays/99",
        )
    }

    @Test
    fun `INV-AC-1727 probe pinnedRule and transition in the contents fail naming the property`() {
        named(violations(withContents { it.put("pinnedRule", "NO_MOVE") }, CalendarSeedFiles.sources), "/pinnedRule")
        named(violations(withContents { it.putObject("transition") }, CalendarSeedFiles.sources), "/transition")
    }
}
