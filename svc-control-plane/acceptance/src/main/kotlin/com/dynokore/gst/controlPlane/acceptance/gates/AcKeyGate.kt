package com.dynokore.gst.controlPlane.acceptance.gates

import java.io.File

/**
 * The acceptance-criterion gate (INV-T-4984, `acKeyCoverage`): `ac-keys.tsv` lists, one row per acceptance criterion
 * the tasks of this service carry, `criterion`, `task`, `wave` (2 for a criterion run by this service's tasks, 3 or 4
 * for a deferred one). It fails when
 * - the table is missing or empty, or a row is malformed;
 * - a row with wave 2 has no test whose name contains the criterion key as a whole token;
 * - a test name holds a criterion key (`INV-AC-<digits>`) that is in no row (so a longer look-alike key fails).
 *
 * A test name is a backticked Kotlin function name in a file of a test source set. Comments and string literals are
 * blanked first, so a name that only a comment or a fixture string holds is no test. `@DisplayName` is not read: a
 * test named only by it does not count, and its row fails closed.
 */
object AcKeyGate {
    const val NAME = "ac-keys.tsv"
    private val HEADER = listOf("criterion", "task", "wave")
    private val KEY = Regex("INV-AC-[0-9]+")
    private val TASK = Regex("INV-T-[0-9]+")
    private val BACKTICK_FUN = Regex("""\bfun\s+`([^`\n]+)`""")
    private val CAMEL_TEST_SET = Regex(".*[a-z0-9]Test")

    /** Test source sets: `test`, `testFixtures` or a camel-case `...Test` name (platform-build's `isTestSourceSet`). */
    fun isTestSourceSet(name: String) = name == "test" || name == "testFixtures" || CAMEL_TEST_SET.matches(name)

    fun testNames(moduleDirs: List<File>): List<Pair<File, String>> =
        moduleDirs.flatMap { module ->
            val sets = File(module, "src").listFiles { f -> f.isDirectory && isTestSourceSet(f.name) }.orEmpty()
            sets.flatMap { set ->
                set
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { file -> namesIn(SourceText.stripCommentsAndStrings(file.readText())).map { file to it } }
                    .toList()
            }
        }

    private fun namesIn(text: String): List<String> = BACKTICK_FUN.findAll(text).map { it.groupValues[1] }.toList()

    /** True when [name] holds [key] as a whole token: not inside a longer key or word. */
    fun holdsKey(
        name: String,
        key: String,
    ): Boolean = Regex("""(?<![A-Za-z0-9-])${Regex.escape(key)}(?![A-Za-z0-9]|-[A-Za-z0-9])""").containsMatchIn(name)

    fun check(
        table: String?,
        testNames: List<Pair<File, String>>,
    ): List<Finding> {
        val parsed = table?.let(Tsv::rows)
        return when {
            parsed == null -> listOf(Finding(NAME, NAME, "the table is missing or empty"))
            parsed.second.isEmpty() -> listOf(Finding(NAME, NAME, "the table has no row"))
            else -> checkRows(parsed.first, parsed.second, testNames)
        }
    }

    private fun checkRows(
        header: List<String>,
        rows: List<List<String>>,
        testNames: List<Pair<File, String>>,
    ): List<Finding> {
        val findings = ArrayList<Finding>()
        if (header != HEADER) findings += Finding(NAME, "header", "expected columns $HEADER, found $header")
        val keys = LinkedHashSet<String>()
        for (row in rows) findings += rowFindings(row, keys, testNames)
        for ((file, name) in testNames) {
            val strays =
                KEY
                    .findAll(name)
                    .map { it.value }
                    .filter { it !in keys }
                    .distinct()
            findings += strays.map { Finding(NAME, it, "test '$name' (${file.name}) names a key that is in no row") }
        }
        return findings
    }

    private fun rowFindings(
        row: List<String>,
        keys: MutableSet<String>,
        testNames: List<Pair<File, String>>,
    ): List<Finding> {
        if (row.size !=
            HEADER.size
        ) {
            return listOf(Finding(NAME, row.joinToString(" "), "a row has the columns $HEADER"))
        }
        val (key, task, wave) = row
        val out = ArrayList<Finding>()
        if (!KEY.matches(key)) out += Finding(NAME, key, "'$key' is not an INV-AC-<n> key")
        if (!TASK.matches(task)) out += Finding(NAME, key, "task '$task' is not an INV-T-<n> key")
        if (wave !in setOf("2", "3", "4")) out += Finding(NAME, key, "wave '$wave' is not 2, 3 or 4")
        if (!keys.add(key)) out += Finding(NAME, key, "listed twice")
        if (wave == "2" && testNames.none { holdsKey(it.second, key) }) {
            out += Finding(NAME, key, "wave 2 row has no test whose name contains the key as a whole token")
        }
        return out
    }
}
