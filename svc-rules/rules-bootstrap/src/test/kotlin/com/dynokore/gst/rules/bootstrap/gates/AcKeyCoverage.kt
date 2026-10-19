package com.dynokore.gst.rules.bootstrap.gates

/**
 * The gate "acceptance-criterion keys, fails closed" (INV-T-4965). `ac-keys.tsv` lists one row per acceptance criterion
 * the tasks of this service carry (criterion, task key, wave as a number: 2 for a criterion run by this service's
 * tasks, 3 or 4 for a deferred one). The gate fails when
 *  - the table is missing, empty, malformed or lists a criterion twice;
 *  - a row with wave 2 has no test whose name contains the criterion key as a whole token;
 *  - a test name holds a key that is in no row.
 *
 * A test is a function annotated `@Test`, `@ParameterizedTest`, `@RepeatedTest` or `@TestFactory` and not `@Disabled`,
 * read from the code of the file (comments removed). A name is cut into tokens at every character that is not a letter,
 * a digit, a hyphen or an underscore, and a key is a token that is exactly `INV-AC-<digits>`; so `INV-AC-17230`,
 * `XINV-AC-1723` and `INV-AC-1723-2` are tokens of their own and never the key `INV-AC-1723`.
 */
internal object AcKeyCoverage {
    data class Row(
        val criterion: String,
        val task: String,
        val wave: Int,
    )

    data class TestName(
        val file: String,
        val name: String,
    )

    private val KEY = Regex("""INV-AC-\d+""")
    private val TASK = Regex("""INV-T-\d+""")
    private val TEST =
        Regex(
            """@(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\b(?<between>[^;{}]{0,600}?)""" +
                """\bfun\s+(?:`(?<bt>[^`]+)`|(?<id>\w+))""",
        )

    const val HEADER = "criterion\ttask\twave"

    fun parse(table: String?): Pair<List<Row>, List<String>> {
        val lines = table?.lines()?.filter { it.isNotBlank() }.orEmpty()
        val problem =
            when {
                table == null -> "ac-keys.tsv is missing"
                lines.firstOrNull() != HEADER -> "ac-keys.tsv must start with the header '$HEADER'"
                else -> null
            }
        if (problem != null) return emptyList<Row>() to listOf(problem)
        val findings = mutableListOf<String>()
        val rows = mutableListOf<Row>()
        lines.drop(1).forEachIndexed { index, line ->
            val cells = line.split('\t')
            val at = "ac-keys.tsv line ${index + 2}"
            val finding = problemOf(cells)?.let { "$at: $it" }
            if (finding == null) rows += Row(cells[0], cells[1], cells[2].toInt()) else findings += finding
        }
        val twice = rows.groupBy { it.criterion }.filterValues { it.size > 1 }.keys
        findings += twice.map { "ac-keys.tsv lists $it more than once" }
        if (rows.isEmpty() && findings.isEmpty()) findings += "ac-keys.tsv has no rows"
        return rows to findings
    }

    private fun problemOf(cells: List<String>): String? =
        when {
            cells.size != 3 -> "expected 3 tab-separated columns"
            !KEY.matches(cells[0]) -> "'${cells[0]}' is not a criterion key INV-AC-<digits>"
            !TASK.matches(cells[1]) -> "(${cells[0]}) '${cells[1]}' is not a task key"
            cells[2] !in setOf("2", "3", "4") -> "(${cells[0]}) wave '${cells[2]}' is not 2, 3 or 4"
            else -> null
        }

    /** The tests of the given Kotlin files: [sources] maps a path to its text. */
    fun testNames(sources: Map<String, String>): List<TestName> =
        sources.flatMap { (file, text) ->
            val code = KotlinSource.withoutComments(text, blankStrings = true)
            TEST
                .findAll(code)
                .filter { match ->
                    "@Disabled" !in match.groups["between"]!!.value &&
                        !Regex("""\bclass\b""").containsMatchIn(match.groups["between"]!!.value)
                }.map { match -> TestName(file, match.groups["bt"]?.value ?: match.groups["id"]!!.value) }
                .toList()
        }

    fun tokens(name: String): List<String> = name.split(Regex("""[^A-Za-z0-9_-]+""")).filter { it.isNotEmpty() }

    /** The findings of the gate, empty when it passes. */
    fun check(
        table: String?,
        sources: Map<String, String>,
    ): List<String> {
        val (rows, findings) = parse(table)
        val tests = testNames(sources)
        val keysInTests = tests.flatMap { test -> tokens(test.name).filter { KEY.matches(it) }.map { it to test } }
        val known = rows.map { it.criterion }.toSet()
        val out = findings.toMutableList()
        rows.filter { it.wave == 2 }.forEach { row ->
            if (keysInTests.none { it.first == row.criterion }) {
                out +=
                    "row ${row.criterion} (task ${row.task}, wave 2) has no test whose name contains the key " +
                    "as a whole token"
            }
        }
        keysInTests.filter { it.first !in known }.forEach { (key, test) ->
            out += "test '${test.name}' in ${test.file} holds the key $key, which is in no row of ac-keys.tsv"
        }
        return out
    }
}
