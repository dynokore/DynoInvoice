package com.dynokore.gst.controlPlane.adapters.seed

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/**
 * Gate (source table, fails closed; INV-AC-2431, INV-AC-10207), as the calendar seed's gate: every leaf of
 * contents.json (and of release.json) has one row, every row names a leaf, and each row carries a kind, class,
 * citation and retrieval date of the right form.
 */
class CodeCatalogueSeedSourcesTest {
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
            val leaves = CalendarSeedFiles.leaves(CodeCatalogueSeedFiles.mapper.readTree(contentsText))
            val rows = CalendarSeedFiles.rows(tsv, problems)
            val byPath = rows.groupBy { it.path }
            leaves.forEach { (path, value) ->
                val own = byPath[path].orEmpty()
                if (own.isEmpty()) problems += "$path: the leaf has no row in the sources table"
                if (own.size > 1) problems += "$path: ${own.size} rows in the sources table"
                own.firstOrNull()?.takeIf { it.value != value }?.let {
                    problems += "$path: the row value ${it.value} is not the leaf $value"
                }
            }
            rows.forEach { problems += rowProblems(it, it.path in leaves) }
            return problems
        }

        private fun rowProblems(
            row: SourceRow,
            known: Boolean,
        ): List<String> {
            val p = row.path
            return listOfNotNull(
                "$p: the row names a path that the contents do not hold".takeIf { !known },
                "$p: the citation is empty".takeIf { row.citation.isBlank() },
                "$p: source kind '${row.kind}' is not REQUIREMENT or LAW".takeIf { row.kind !in KINDS },
                "$p: source class '${row.sourceClass}' is not PRIMARY, SECONDARY or INFERRED"
                    .takeIf { row.sourceClass !in CLASSES },
                "$p: the REQUIREMENT citation '${row.citation}' is not a Keel key INV-AC-n or INV-REQ-n"
                    .takeIf { row.kind == "REQUIREMENT" && !KEEL_KEY.matches(row.citation) },
                "$p: the LAW row has no retrieval date".takeIf { row.kind == "LAW" && !DATE.matches(row.retrievedOn) },
            )
        }

        fun withContents(edit: (ObjectNode) -> Unit): String {
            val root = CodeCatalogueSeedFiles.mapper.readTree(CodeCatalogueSeedFiles.contents) as ObjectNode
            edit(root)
            return root.toString()
        }

        fun withRow(
            path: String,
            edit: (List<String>) -> List<String>?,
        ): String =
            CodeCatalogueSeedFiles.sources
                .split("\n")
                .mapNotNull { line ->
                    val c = line.split("\t")
                    if (c[0] == path) edit(c)?.joinToString("\t") else line
                }.joinToString("\n")
    }

    private fun named(
        problems: List<String>,
        path: String,
    ) = problems.any { it.startsWith("$path:") } shouldBe true

    private fun probe(
        path: String,
        column: Int,
        value: String,
    ) = violations(
        CodeCatalogueSeedFiles.contents,
        withRow(path) { it.toMutableList().also { c -> c[column] = value } },
    )

    @Test
    fun `INV-AC-2431 the shipped sources tables cover every leaf and pass every check`() {
        violations(CodeCatalogueSeedFiles.contents, CodeCatalogueSeedFiles.sources).shouldBeEmpty()
        violations(CodeCatalogueSeedFiles.release, CodeCatalogueSeedFiles.releaseSources).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-2431 probe a deleted row fails naming the path`() {
        named(
            violations(CodeCatalogueSeedFiles.contents, withRow("/codes/9/annexERate") { null }),
            "/codes/9/annexERate",
        )
    }

    @Test
    fun `INV-AC-2431 probe an empty citation fails naming the path`() {
        named(probe("/codes/0/code", 4, ""), "/codes/0/code")
    }

    @Test
    fun `INV-AC-2431 probe a source kind OTHER fails naming the path`() {
        named(probe("/codes/0/code", 2, "OTHER"), "/codes/0/code")
    }

    @Test
    fun `INV-AC-2431 probe a lower case class fails naming the path`() {
        named(probe("/codes/0/sourceClass", 3, "primary"), "/codes/0/sourceClass")
    }

    @Test
    fun `INV-AC-2431 probe a LAW row without a retrieval date fails naming the path`() {
        named(probe("/codes/0/group", 5, ""), "/codes/0/group")
    }

    @Test
    fun `INV-AC-2431 probe a leaf without a row and a row for a missing path fail naming the path`() {
        named(
            violations(
                withContents { (it.get("codes").get(0) as ObjectNode).put("extra", "x") },
                CodeCatalogueSeedFiles.sources,
            ),
            "/codes/0/extra",
        )
        val extra = "/codes/99/code\t\"X\"\tLAW\tPRIMARY\tR1 Annex E page 79\t2026-10-28"
        named(
            violations(
                CodeCatalogueSeedFiles.contents,
                CodeCatalogueSeedFiles.sources.trimEnd('\n') + "\n" + extra + "\n",
            ),
            "/codes/99/code",
        )
    }
}
